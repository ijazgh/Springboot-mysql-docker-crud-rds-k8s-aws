# Cost, reuse and cleanup checklist

**No AWS cleanup has been performed.** This is the checklist for the next phase, after you review the new project and decide whether to deploy it. Do not remove the old database until you have decided whether to preserve its records and verified your export/restore.

## What can be reused

| Existing item | New project needs it? | Cost/decision |
|---|---|---|
| EKS cluster `springboot-crud-app-cluster` | Yes for the EKS exercise | Control-plane charges continue even with zero worker nodes; EKS cannot simply be stopped like a laptop |
| Managed node group `springboot-workers` | Yes while app runs | EC2 and node disks cost while provisioned; do not stop managed EC2 instances manually as a cost strategy |
| VPC/private subnets/routes/security groups | Yes for private EKS↔RDS connection | NAT gateways and public IPv4 addresses can add charges; do not remove routes used by the cluster |
| GitHub deploy IAM user and EKS access entry | Yes, existing `default` scope fits | No standalone hourly IAM role/user charge; credentials still require secure storage and rotation |
| CoreDNS, VPC CNI, kube-proxy | Yes | Cluster components use worker resources |
| Pod Identity Agent | Existing controller may use it | Keep while dependent components use it |
| AWS Load Balancer Controller and its IAM role/policy | Only needed for optional public service or existing apps | Keep during migration; controller pods consume node resources, the load balancer itself is separately billed |
| Old app's public NLB | New default ClusterIP does not need it | Delete old Kubernetes Service after verification, then verify AWS removes the NLB and target groups |
| Old MySQL Deployment, Service, Secret, ConfigMap | Not used by new project | Remove only after backup/data decision and old-app retirement |
| Old MySQL PVC, PV and dedicated EBS data volume | Not used by RDS | Retain policy means deleting the PVC does NOT automatically delete the disk; inspect exact EBS volume ID and dependencies |
| EBS CSI driver / its Pod Identity IAM role | RDS app does not need it | Other workloads might; do not uninstall before inventory. Driver removal does not delete EBS volumes |
| StorageClass `mysql-ebs-gp3` | New app does not use it | StorageClass definition itself does not allocate a disk |
| GitHub and Docker Hub accounts | Yes | Existing secrets must be configured per repository; check plan/usage limits |

RDS is a separate database service; its internal storage is not your old Kubernetes PVC. Deleting an old MySQL data EBS volume does not remove EC2 worker boot volumes. Distinguish those carefully.

## Phase A — inventory and backup

Confirm the current kubectl context is the correct EKS cluster. Save the outputs below privately for review (they do not print Secret values):

```powershell
kubectl config current-context
kubectl get deployments,services,pvc -n default
kubectl get pv
kubectl get volumeattachments
aws eks list-nodegroups --cluster-name springboot-crud-app-cluster --region us-east-2
aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[].{Name:LoadBalancerName,DNS:DNSName,Arn:LoadBalancerArn}'
aws ec2 describe-nat-gateways --region us-east-2 --filter Name=state,Values=available --query 'NatGateways[].{Id:NatGatewayId,Vpc:VpcId}'
aws rds describe-db-instances --region us-east-2 --query 'DBInstances[].{Id:DBInstanceIdentifier,Status:DBInstanceStatus,Public:PubliclyAccessible}'
```

Export the old MySQL database using `mysqldump` through a private/interactive credential mechanism. Keep the dump outside Git, or encrypted with tightly restricted access. Do not redirect raw SQL to a publicly shared folder. Restore into the intended RDS schema and confirm records if migration is desired. A fresh empty database is also valid for learning if you explicitly choose to discard old records.

## Phase B — retire old app and its public service

After the new app is tested and you approve retirement, the old names observed were `springboot-crud-deployment` and `springboot-crud-svc`. Verify them against current inventory. Delete the old Deployment and Service by their exact names only. Do not delete the entire default namespace or apply broad label selectors that could match the new app.

Deleting the old LoadBalancer Service lets the AWS Load Balancer Controller clean up its NLB. Keep the controller running until that cleanup finishes. Check in EC2 → Load Balancers / Target Groups that the specific old resources disappeared. Do not manually delete arbitrary shared load balancers/security groups.

The new service is `springboot-rds-crud`. By default it is ClusterIP and uses no NLB. If you deliberately used the optional public manifest, that public service creates a separate load balancer.

## Phase C — retire old MySQL storage carefully

After backup/restore verification and approval, remove the old MySQL Deployment so the volume is no longer in use. Confirm no pod still mounts the old claim. Record the actual PV and EBS ID BEFORE deleting the PVC:

```powershell
kubectl get pvc mysql-pv-claim-fresh -n default -o wide
$oldPvName = kubectl get pvc mysql-pv-claim-fresh -n default -o jsonpath='{.spec.volumeName}'
kubectl get pv $oldPvName -o yaml
kubectl get pv $oldPvName -o jsonpath='{.spec.csi.volumeHandle}'
```

The output contains the authoritative EBS data volume ID. Confirm in EC2 → Volumes that it is the intended old database volume, not a node root disk. With `Retain`, the disk can remain billable after PVC deletion. Plan explicit removal of the old claim, released PV object and verified EBS volume once data disposal is authorized. Snapshots are separate resources and can also be billed.

Remove obsolete old Secret/ConfigMap/headless Service only after confirming no other pod uses them. EBS CSI and IAM resources can remain until all storage users are checked; they are not prerequisites for this new RDS app.

## Phase D — end a learning session

Choose deliberately:

* **Local only:** stop local app/Compose; do not create cloud resources. Existing AWS resources still need separate cleanup.
* **Pause new app:** scaling its replicas to zero stops its workload, but does not stop RDS, EKS, EC2, NAT, disks or load balancer charges. The next workflow apply restores one replica.
* **Short RDS pause:** a stopped supported RDS instance retains storage and backup costs, and automatically starts after seven days. It is not a permanent free pause.
* **Full cloud teardown:** inventory dependencies; export data or choose final snapshots (which can cost); delete app Services/load balancers, then databases, worker groups, cluster and unneeded supporting network/storage resources in a reviewed dependency order. Keep reusable code and IAM definitions. Rebuilding a cluster later requires a separate setup session.

Retaining the EKS cluster as requested conflicts with a strict zero-running-cost goal. You can retain IAM roles/policies and source code while deleting paid infrastructure; keeping only those definitions is the cheaper way to rebuild later. Do not provision a second cluster for this project.

## Verify billing after cleanup

Review AWS Console in **every used region** for EC2, EBS, snapshots, ELB, NAT, public IPs, RDS instances/snapshots, CloudWatch logs and retained backups. Check Cost Explorer/Billing after reporting catches up; resource disappearance and billing reports have different timing. Budget alerts do not stop resources automatically.

Sources: [EKS pricing](https://aws.amazon.com/eks/pricing/), [RDS pricing](https://aws.amazon.com/rds/pricing/), [RDS stopping rules](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html).

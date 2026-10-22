# Rebuild and deployment walkthrough

Follow in order. Commands marked PowerShell run in your project terminal; workflow scripts use Linux Bash. Do not paste Bash lines into PowerShell. Replace placeholders before executing. This guide does not assume that RDS is already created.

## 1. Open the separate project

Directory: `C:\Users\dell\Desktop\Springboot-mysql-docker-crud-rds-k8s-aws`.

In IntelliJ: File → Open → choose this directory or `pom.xml` → Open as Project. Settings → Build Tools → Maven → Runner: use Project JDK. Project Structure → SDK: Corretto 26. Wait for Maven import. Your original project remains separate.

```powershell
java -version
mvn -version
docker version
aws --version
kubectl version --client
```

Both Java and Maven should report Java 26. A restarted terminal may be needed after installing tools. You do not need Helm to run this application's workflow because your existing cluster already has the load balancer controller.

## 2. Practice locally first

Follow the README local commands. `mvn verify` uses H2; it does not connect to MySQL or AWS. Running the main application uses the `local` profile by default and needs the Compose MySQL instance. The sample passwords in Compose are public local-learning values and must never become RDS passwords. MySQL binds only to localhost.

If local port 3306 is occupied, stop the conflicting local MySQL or map `3307:3306` in Compose and set `$env:DB_PORT='3307'` for your application terminal.

## 3. Confirm what you already have (read-only)

```powershell
aws sts get-caller-identity
aws eks describe-cluster --name springboot-crud-app-cluster --region us-east-2 --query 'cluster.{Status:status,Vpc:resourcesVpcConfig.vpcId,Subnets:resourcesVpcConfig.subnetIds}'
aws eks update-kubeconfig --name springboot-crud-app-cluster --region us-east-2
kubectl config current-context
kubectl get nodes
kubectl get pods -n kube-system
```

`update-kubeconfig` only changes local connection configuration. Confirm you see EKS worker nodes rather than `docker-desktop` before any apply/delete command. Existing VPC observed during the previous setup: `vpc-050de3e9371706c1f`; confirm it from current output. Never rely solely on a historical ID.

Keep the current cluster and node group for this migration if you accept their running costs. Keep the existing GitHub deploy IAM user/access entry in namespace `default`. New app resource names prevent overwriting the old app. Both apps can run simultaneously, which also uses more capacity and may duplicate costs.

## 4. Make the cost decision BEFORE creating RDS

Open AWS Billing → Budgets and configure a low learning budget and email alerts. Check the current EKS and RDS estimates in your account. A budget alert does not impose a hard spending limit. Account credits/free-tier eligibility vary; do not assume RDS is free.

For local learning, stay at step 2 and create nothing in AWS. For the cloud exercise, plan a short session and cleanup afterward. Do not leave RDS, EKS nodes, NAT or load balancers running indefinitely. Stopped RDS automatically restarts after seven days and its storage can still be charged.

## 5. Create private RDS networking

Use **us-east-2** and the **same VPC as EKS**. In VPC → Subnets identify at least two PRIVATE subnets in different Availability Zones. Check their route tables; do not guess privacy from the subnet name. RDS needs a DB subnet group spanning at least two AZs, even for a Single-AZ DB instance.

RDS → Subnet groups → Create DB subnet group:

* Name: `springboot-rds-subnets`.
* VPC: the verified EKS VPC.
* Select the private subnets in two AZs.

EC2 → Security Groups → Create `springboot-rds-db-sg` in the same VPC. Add one inbound TCP 3306 rule whose **source is the actual EKS worker node security group** used for your pod traffic. Find worker instances from EKS node group → EC2 instances → Security. If using security groups for pods, use the pod group instead. Do not allow `0.0.0.0/0` or your public laptop IP. Existing node egress/network ACLs must permit the connection and return traffic. No internet-facing DB is needed.

## 6. Create the RDS MySQL instance

RDS → Databases → Create database → Standard create → MySQL (not Aurora for this exercise).

* Choose a supported MySQL 8.4 version offered in your account/region, compatible with Connector/J.
* Pick the smallest suitable supported instance and inspect the estimated charges. Use the Dev/Test template and Single-AZ for this learning session; availability is lower than production Multi-AZ.
* Identifier: `springboot-learning-mysql`; administrator name such as `dbadmin`. Save the administrator password privately, or use RDS-managed credentials with Secrets Manager (additional cost can apply).
* Choose modest gp3 storage within RDS's allowed minimum. Review storage autoscaling and its maximum, backup retention, logs and monitoring add-ons for cost.
* VPC: verified EKS VPC; DB subnet group: `springboot-rds-subnets`; **Public access: No**; existing SG: `springboot-rds-db-sg`. Remove an unnecessary default SG if selected.
* Turn on storage encryption. Initial database name: `javatechie`. Review backup retention, maintenance window and deletion protection deliberately.
* Review the estimated cost, then create only when ready for the cloud exercise.

Wait until Available. Copy its **endpoint hostname** from Connectivity & security (no `https://`, no `:3306`, no `/javatechie`). This will become GitHub variable `RDS_HOST`. RDS patching and backups follow your configured settings; you still own app compatibility and restore testing.

## 7. Initialize the application database user

Your laptop cannot directly reach this private endpoint. Use a temporary MySQL client pod in EKS (with your local admin kubectl identity) or an approved SSM-connected host in the VPC. Do not make RDS public just to initialize it.

Prepare the public CA truststore on the laptop (Python 3 and JDK `keytool` required):

```powershell
python scripts/prepare-rds-truststore.py
```

For the MySQL client also download AWS's public CA bundle into the ignored certs directory:

```powershell
Invoke-WebRequest https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -OutFile certs/global-bundle.pem
kubectl create configmap rds-client-ca -n default --from-file=global-bundle.pem=certs/global-bundle.pem
```

Create a temporary client pod from `k8s/mysql-client.yaml`:

```powershell
kubectl apply -f k8s/mysql-client.yaml
kubectl wait --for=condition=Ready pod/rds-mysql-client -n default --timeout=180s
kubectl exec -it rds-mysql-client -n default -- mysql --host=YOUR_RDS_ENDPOINT --port=3306 --user=dbadmin --password --ssl-mode=VERIFY_IDENTITY --ssl-ca=/certs/global-bundle.pem
```

Enter the administrator password only at the prompt. Open `sql/bootstrap-rds.sql`, replace its example password privately, and run its SQL in the MySQL prompt. Set a UNIQUE application password. Do not commit the edited password file. `REQUIRE SSL` enforces encryption for the application user. The application gets access only to its own schema, not the RDS administrator identity. Exit MySQL and remove the temporary pod and CA ConfigMap:

```powershell
kubectl delete pod rds-mysql-client -n default
kubectl delete configmap rds-client-ca -n default
```

The fresh database has no old orders. If you want the existing data, export the old database and import into RDS BEFORE deleting the old PVC/EBS volume; confirm row counts and app reads afterward. Do not copy MySQL's disk files between servers.

## 8. Create the new GitHub repository

GitHub account → + → New repository → name `Springboot-mysql-docker-crud-rds-k8s-aws`. Choose visibility. Do NOT initialize with README, .gitignore or license because this directory already has files. Create repository.

In the new project directory (not the original):

```powershell
git init -b main
git add .
git status
# Inspect staged files before commit: no passwords, .env, credentials or edited SQL secrets.
git diff --cached
git commit -m "Create Spring Boot RDS learning application and manual EKS deployment"
git remote add origin https://github.com/ijazgh/Springboot-mysql-docker-crud-rds-k8s-aws.git
git push -u origin main
```

Alternatively use IntelliJ → VCS → Enable Version Control Integration → Git, then Git → GitHub → Share Project. Pick ONE method. If you already initialized Git, do not repeat init/remote-add. If you already created a nonempty remote, inspect its history before merging; do not force-push blindly.

## 9. Configure Docker Hub and GitHub

Docker Hub → create a **public** repository named `springboot-mysql-rds` under `ijazdh`. Public image access avoids needing an imagePullSecret. If choosing private images, add registry pull credentials and imagePullSecrets before deployment. Use a Docker Hub access token with only required repository write permissions.

New GitHub repository → Settings → Secrets and variables → Actions → Secrets → New repository secret:

| Name | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | Existing `springboot-github-deploy` IAM user's key ID, if you retained it securely |
| `AWS_SECRET_ACCESS_KEY` | Matching secret key; never paste into chat or source files |
| `DOCKER_USERNAME` | `ijazdh` (Docker account; GitHub account is `ijazgh`) |
| `DOCKER_PASSWORD` | Docker Hub access token |
| `DB_USERNAME` | `springapp` |
| `DB_PASSWORD` | Application password created in step 7 |

Under **Variables**, create `RDS_HOST` with your verified endpoint hostname. No `MYSQL_ROOT_PASSWORD` is needed. Old repository secrets do not automatically appear here. GitHub cannot reveal secret values to copy them back. If you no longer possess the AWS secret, arrange a deliberate key rotation rather than creating keys repeatedly.

Reuse the existing IAM user policy allowing `eks:DescribeCluster` on `springboot-crud-app-cluster` and its EKS STANDARD access entry with `AmazonEKSEditPolicy` scoped to `default`. This workflow stays in `default` to match those permissions. Kubernetes accesses RDS with the database user/password; no new IAM role is required for password-based RDS access. The workflow does not provision RDS or modify IAM. For a future production setup, prefer short-lived GitHub OIDC credentials when your account supports it; your previous account restrictions prevented that route.

## 10. Test first, then deploy manually

GitHub → Actions → **Build and deploy RDS app**. A normal main push or pull request runs tests only. The workflow file must be on the repository's default branch for the manual Run workflow button.

For AWS deployment: Actions → workflow → Run workflow → main → check **deploy** → Run workflow. This builds/tests, prepares the public CA truststore, publishes the commit-tagged image, connects to the EXISTING cluster, applies new ConfigMap/Secret/Deployment/ClusterIP Service, then waits for rollout. It creates no RDS, cluster, EBS PVC or load balancer. Failed configuration stops deployment; earlier image publishing may still have occurred.

Read success/failure in the test and deploy jobs. A timeout does not roll back or remove resources automatically. Inspect the pod events and logs before rerunning.

## 11. Connect without a paid load balancer

```powershell
kubectl get pods,services -n default -l app=springboot-rds-crud
kubectl rollout status deployment/springboot-rds-crud -n default
kubectl port-forward service/springboot-rds-crud -n default 8080:8080
```

Leave that terminal open. In another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
$order = @{name='RDS order'; qty=1; price=39.99} | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/orders -Method Post -ContentType 'application/json' -Body $order
Invoke-RestMethod http://localhost:8080/orders
```

Port-forwarding needs Kubernetes access and is for your learning session. The browser reaches localhost; the EKS pod reaches private RDS. There is no Kubernetes MySQL Service/PVC in this project. RDS manages its own underlying storage.

OPTIONAL: a public endpoint costs more and exposes this unauthenticated learning API. Only for a deliberate short test, apply `k8s/service-public.yaml`, then get the service hostname and use `http://HOSTNAME:8080/orders`. The existing AWS Load Balancer Controller/Pod Identity role can be reused. Do not submit real sensitive data over this HTTP endpoint. Reapplying the ClusterIP manifest through the workflow changes this service back to private and causes load balancer cleanup; verify AWS deletion afterward. For a public production app add HTTPS and authentication first.

## 12. Rehearse persistence and follow cleanup

Create a record, restart ONLY the new Deployment with `kubectl rollout restart deployment/springboot-rds-crud -n default`, wait for readiness, then GET `/orders`. It should still return the RDS record. This confirms storage outside the pod; it does not prove backups or failover.

Read `docs/COST-AND-CLEANUP.md` before removing the old database. We have not deleted any old resource. Successful deployment and data checks come first.

## Troubleshooting

| Symptom | Check |
|---|---|
| Workflow only CI visible | New file must be under `.github/workflows/` on default branch; inspect Actions errors |
| Forbidden from kubectl in Actions | AWS credentials identity, EKS access entry, `default` namespace permission |
| DB connection timeout | Correct RDS hostname, same VPC/routing, 3306 source SG for node/pod traffic, NACLs, RDS Available |
| Access denied for springapp | User exists in RDS, matching DB_PASSWORD secret, schema grants and required SSL |
| PKIX / hostname verification failure | Rebuild truststore using script; use exact RDS endpoint; never fix by disabling TLS verification |
| ImagePullBackOff | Docker Hub repository public, username is ijazdh, image pushed, node registry access |
| Pending / insufficient memory | Existing worker capacity, old deployments still running, CPU/memory requests |
| CrashLoopBackOff | `kubectl logs deployment/springboot-rds-crud -n default --tail=100`; inspect without posting secrets |
| Readiness DOWN | Database connectivity/availability; liveness intentionally does not restart app merely for a DB outage |

## Sources

* https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ConnectToInstanceSSL.CLI.html
* https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/ssl-certificate-rotation-mysql.html
* https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_VPC.WorkingWithRDSInstanceinaVPC.html
* https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html
* https://aws.amazon.com/eks/pricing/
* https://aws.amazon.com/rds/pricing/

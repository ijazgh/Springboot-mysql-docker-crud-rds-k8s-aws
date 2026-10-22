# Spring Boot + private RDS + public HTTPS login on EKS

This learning application exposes its order API through a public AWS Network Load Balancer using HTTPS and Spring Security login. RDS remains private. See [PUBLIC-LOGIN-SETUP.md](PUBLIC-LOGIN-SETUP.md) for the CURRENT deployment and login instructions.

The original [RDS walkthrough](docs/STEP-BY-STEP.md) still explains the VPC, database and repository setup. Its private-Service deployment and anonymous API examples describe the earlier version and are superseded by the public-login guide.

## Local development

Use Java 26 and Maven. Start the local MySQL database with Docker Compose, then run the app with the local profile. If another MySQL uses 3306, use the existing local test database on 3307 and set `DB_PORT=3307` in IntelliJ. The local default login is `learner` / `local-login-only`; open `http://localhost:8080/login`. Override `APP_LOGIN_USERNAME` / `APP_LOGIN_PASSWORD` if desired. Never use these public local-learning credentials for an AWS deployment.

```powershell
mvn verify
mvn spring-boot:run
```

`mvn verify` uses H2 and generates a short-lived test certificate; it does not create AWS infrastructure. Tests cover authentication, login/logout, CSRF, order persistence, health checks and HTTPS with a trusted test certificate.

## GitHub configuration

Keep `RDS_HOST` as a repository variable. Keep AWS, Docker Hub, DB_USERNAME and DB_PASSWORD secrets. Add APP_LOGIN_USERNAME and APP_LOGIN_PASSWORD as repository Actions secrets, with a distinct login password of at least 12 characters.

Main pushes and PRs run tests only. Run the workflow manually with **deploy** checked to publish an image and update the EXISTING EKS cluster. The workflow creates the public NLB Service, generates a seven-day self-signed certificate for its hostname, and deploys profiles `rds,public`. The NLB selector matches only the new TLS pods. The login and certificate are kept in Kubernetes Secrets.

## Public access

Open `https://YOUR_NLB_HOSTNAME/orders`; no port-forwarding is needed after deployment. Expect a browser certificate warning because you selected temporary self-signed HTTPS without a domain. Only trust the hostname verified from your own Service. A trusted production certificate requires an appropriate domain/CA setup. Redeployment regenerates the temporary certificate; this is not an automatically renewed permanent website.

The app has one in-memory learning user and no registration/password-reset system. Order reads require authentication. Order writes also require CSRF protection; see the public-login guide for API details. The generated browser login page redirects successful login to the orders JSON. RDS credentials and login credentials are separate.

## Kubernetes files

* `deployment-public.yaml` + `service-public.yaml`: CURRENT public HTTPS deployment. Port 443 forwards encrypted TCP to app port 8443; health checks run separately on internal port 8081.
* `deployment.yaml` + `service.yaml`: alternative PRIVATE authenticated HTTP deployment for port-forwarding. Requires the login Secret as well. Do not apply this pair over the public service without planning the switch and verifying NLB cleanup.
* `mysql-client.yaml`: temporary database setup tool; delete after use.

Do not apply the whole k8s directory because it contains alternative manifests.

## Costs and cleanup

Public NLBs/IPs add charges. EKS, worker nodes, NAT, RDS and storage remain billable while provisioned. Follow [the cleanup checklist](docs/COST-AND-CLEANUP.md). Delete the public Service and confirm its AWS load balancer is removed BEFORE deleting the cluster/controller. Nothing in the test job deletes or provisions a database.

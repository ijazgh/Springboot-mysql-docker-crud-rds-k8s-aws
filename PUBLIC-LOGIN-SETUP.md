# Public HTTPS with a login — temporary learning deployment

The public URL is `https://LOAD_BALANCER_HOSTNAME/orders`. Spring Security redirects browser HTML requests to its generated login page. Use the credentials from GitHub secrets `APP_LOGIN_USERNAME` and `APP_LOGIN_PASSWORD`; these are separate from MySQL credentials. This is one shared learning user stored in memory, with a BCrypt password hash. It has no registration, account database, password reset or account lockout system. Do not use real sensitive records.

## Required GitHub secrets

Keep existing AWS, Docker Hub and database secrets. Add:

* `APP_LOGIN_USERNAME`: your chosen login name.
* `APP_LOGIN_PASSWORD`: a unique password of at least 12 characters. Do not reuse the database administrator or database application password.

Do not put real credentials in source files. The deployment creates `rds-crud-login` and a separate TLS Secret. The private key and keystore password remain in the TLS Secret, not the image/repository.

## Deploy

Review and commit the updated files, push main, then run **Build and deploy RDS app** manually with **deploy** checked. A normal push still runs tests only. The workflow now applies `k8s/service-public.yaml` and `k8s/deployment-public.yaml` rather than the private pair.

The workflow creates the public NLB Service with a selector matching only new HTTPS pods. It waits for the AWS hostname, generates a seven-day self-signed certificate for that exact hostname, stores it in Kubernetes, then rolls out the authenticated application. There may be a brief outage during the change. A failed rollout may leave a billable load balancer; inspect and clean it up deliberately.

HTTPS uses port 443 externally and 8443 inside the app. There is no public HTTP listener. TCP passes encrypted traffic through the NLB to Spring Boot. RDS stays private and its existing verified database TLS configuration is unchanged. Health checks use internal port 8081, which is not exposed by the public Service. Liveness remains independent of database availability.

## Open the app

```powershell
kubectl get service springboot-rds-crud -n default
```

Use `https://HOSTNAME/orders`, without `:8080`. A browser certificate warning is EXPECTED because this short-lived certificate is self-signed. Only continue for the exact hostname retrieved from your own cluster. This certificate encrypts the connection but is not independently trusted by your browser until you explicitly trust it. For trusted public access, use a domain you control and a CA-issued certificate. Redeploy regenerates the temporary certificate; it expires after seven days. Do not use this setup as a permanent production website.

After accepting the expected certificate warning, log in. Successful login shows the order JSON. Visiting `/logout` offers the Spring Security logout confirmation page. Sessions expire after 15 minutes of inactivity. Restarting the sole pod also ends sessions.

## API calls and CSRF

Basic authentication is supported for API clients over HTTPS. CSRF protection remains enabled: POST requests need both an authenticated request and a CSRF token with its associated session cookie. First GET `/api/csrf` with authentication and retain the cookie; then send its `token` under the returned `headerName` when posting JSON to `/orders`. Do not disable CSRF just to make a POST work. Never send login credentials over HTTP or disable certificate verification globally.

## Local development

Default local username is `learner` and local password is `local-login-only`. These public defaults apply only to the local profile. Local browser: `http://localhost:8080/login`. RDS deployment requires externally supplied login secrets and uses profiles `rds,public`.

## Cost and teardown

This creates a billable internet-facing NLB and public IP addresses. Existing EKS, EC2, NAT and RDS charges continue. Delete the public Kubernetes Service while the controller/cluster are still working, then verify the associated AWS NLB is removed before tearing down the cluster. Authentication and TLS Secrets do not themselves allocate a load balancer. Do not delete RDS or its security group as part of merely changing app access.

## Verify after deployment

* Anonymous API GET `/orders` returns 401; a browser HTML request redirects to login.
* Wrong password cannot read orders.
* Browser login reads existing RDS records.
* POST without a CSRF token returns 403.
* The public Service exposes only 443; the database remains non-public.

Original setup documentation describing anonymous access, unauthenticated POST examples and the default private workflow is superseded by this guide for this public-login version.

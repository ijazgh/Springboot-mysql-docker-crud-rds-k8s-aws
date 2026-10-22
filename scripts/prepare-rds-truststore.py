"""Download public AWS RDS CA certificates and build a Java truststore. Requires JDK keytool."""
from pathlib import Path
import re, shutil, subprocess, tempfile, urllib.request
root = Path(__file__).resolve().parents[1]
keytool = shutil.which("keytool")
if not keytool:
    raise SystemExit("Add your JDK bin directory to PATH first (keytool is required).")
bundle = urllib.request.urlopen("https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem", timeout=60).read().decode("ascii")
certs = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", bundle, re.S)
if not certs:
    raise SystemExit("AWS response did not contain certificates.")
out = root / "certs" / "rds-truststore.p12"
out.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory() as temporary:
    folder = Path(temporary)
    store = folder / "rds.p12"
    for index, certificate in enumerate(certs):
        pem = folder / f"ca-{index}.pem"
        pem.write_text(certificate, encoding="ascii")
        subprocess.run([keytool, "-importcert", "-noprompt", "-alias", f"rds-{index}",
                        "-file", str(pem), "-keystore", str(store), "-storetype", "PKCS12",
                        "-storepass", "changeit"], check=True, capture_output=True)
    shutil.copyfile(store, out)
print(f"Created {out} with {len(certs)} public CA certificates.")
# 'changeit' protects a store of PUBLIC certificates; it is not a database credential.

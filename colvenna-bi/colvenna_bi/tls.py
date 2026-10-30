"""The pod's TLS key and self-signed certificate, made at start (an init container): the load
balancer encrypts to its targets without checking their certificates, and only it reaches the pod.

usage: python -m colvenna_bi.tls <directory>
"""

import datetime
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def main(directory):
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "colvenna-bi")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(hours=1))
            .not_valid_after(now + datetime.timedelta(days=365)).sign(key, hashes.SHA256()))
    with open(os.path.join(directory, "tls.key"), "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    with open(os.path.join(directory, "tls.crt"), "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))


if __name__ == "__main__":
    main(sys.argv[1])

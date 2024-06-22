These are test certificates for the target server and the proxy, both signed by the same certificate authority.

They were generated with the following commands (where all passwords are just `password`):

```bash
openssl req -newkey rsa:4096 -keyform PEM -keyout ca.key -x509 -days 3650 -outform PEM -out ca.cer
openssl pkcs12 -export -inkey ca.key -in ca.cer -out ca.p12
openssl genrsa -out target-server.key 4096
openssl req -new -key target-server.key -out target-server.req
openssl genrsa -out proxy-server.key 4096
openssl req -new -key proxy-server.key -out proxy-server.req
openssl x509 -req -in target-server.req -CA ca.cer -CAkey ca.key -set_serial 101 -days 365 -outform PEM -out target-server.cer
openssl x509 -req -in proxy-server.req -CA ca.cer -CAkey ca.key -set_serial 101 -days 365 -outform PEM -out proxy-server.cer
openssl pkcs12 -export -inkey target-server.key -in target-server.cer -out target-server.p12
openssl pkcs12 -export -inkey proxy-server.key -in proxy-server.cer -out proxy-server.p12
```

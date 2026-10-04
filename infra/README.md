# Infraestrutura do JavAI

Como pôr o lado CRUD (Fase 1) no ar na VPS da Hostinger. As decisões estão na [spec 01](../specs/01-arquitetura.md#infraestrutura); aqui ficam só os passos.

| Serviço | O que faz | Porta publicada |
|---|---|---|
| `caddy` | TLS de origem, Authenticated Origin Pulls, `admin.` e `conteudo.` | 443 (→ 8443 no contêiner) |
| `postgres-admin` | Banco do CRUD, na rede interna `admin-data` | nenhuma |
| `admin-migrate` | Bootstrap dos papéis e Flyway; roda e sai | nenhuma |
| `admin-api` | CRUD, publicação, auditoria | nenhuma |

As imagens vêm do GHCR, publicadas pelo CI a cada push na `main`. A VPS nunca compila nada.

## 1. Cloudflare (uma vez)

1. **Certificado de origem:** SSL/TLS → Origin Server → **Create Certificate**.
   - Chave RSA (2048), hosts `*.gilvanborges.com.br` e `gilvanborges.com.br`, validade de 15 anos.
   - Guarde o certificado como `origin.pem` e a chave como `origin.key`. A chave só aparece uma vez.
2. **CA do Authenticated Origin Pulls:** baixe [`authenticated_origin_pull_ca.pem`](https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem) e salve como `cloudflare-origin-pull-ca.pem`.
3. **Ligar o Authenticated Origin Pulls:** SSL/TLS → Origin Server → **Authenticated Origin Pulls: On**.
4. **Modo SSL:** SSL/TLS → Overview → **Full (strict)**. Isso vale para o domínio inteiro, então abra o portfólio logo depois e confira se continua no ar.
5. **Access, para o admin:** Zero Trust → Access → Applications → **Add an application → Self-hosted**.
   - **Domínio:** `admin.gilvanborges.com.br`.
   - **Política:** *Allow*, com *Emails* = o seu e-mail. Use um login com 2FA (passkey ou chave física, de preferência).
   - **Sessão:** 8 horas.
   - **Cookie:** `SameSite=Strict` e `HttpOnly`, em Settings → Cookies.
   - **Guarde dois valores:** a **AUD** (em Overview) e o nome do **time** (em Settings → Custom Pages, `<time>.cloudflareaccess.com`).
6. **`www`:** Rules → Templates → **Redirect from WWW to root**, com 301.

## 2. VPS (uma vez)

**Estado em 2026-10-03:** esta seção já foi aplicada na VPS (Ubuntu 26.04, `javai-vps` na Tailscale). Ela fica aqui para reconstruir o servidor do zero.

Faça na ordem e **não feche a sessão atual antes de testar a nova**. Antes de mexer no SSH ou no `ufw`, deixe um rollback agendado, que só é cancelado depois que uma conexão nova funcionar:

```bash
systemd-run --unit=javai-rollback --on-active=300 /bin/sh -c 'ufw --force disable; rm -f /etc/ssh/sshd_config.d/10-javai.conf; systemctl reload ssh'
# ...mudanças e teste numa conexão nova...
systemctl stop javai-rollback.timer
```

Se algo travar o SSH, o terminal do navegador do hPanel continua funcionando.

**Sistema e usuário:**

```bash
apt update && apt full-upgrade -y
adduser gilvan && usermod -aG sudo gilvan
# copie a sua chave pública para /home/gilvan/.ssh/authorized_keys (chmod 700 na pasta, 600 no arquivo)
```

**Tailscale:** o SSH só vai existir dentro dela.

```bash
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up --hostname=javai-vps --accept-dns=false
tailscale ip -4      # anote; teste "ssh gilvan@<esse-ip>" de outro terminal antes de continuar
# No painel da Tailscale: Machines → javai-vps → Disable key expiry (senão ela sai da tailnet em ~180 dias)
```

**SSH só com chave e sem root:**

```bash
# 10- vem antes do 50-cloud-init.conf da Hostinger, que liga a senha; no sshd vale o primeiro valor lido.
cat > /etc/ssh/sshd_config.d/10-javai.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
AllowUsers gilvan
MaxAuthTries 3
X11Forwarding no
EOF
sshd -t && systemctl reload ssh
```

**`ufw`:** protege o próprio sistema. Ele **não** filtra as portas do Docker; quem faz isso é o firewall do painel, no passo seguinte.

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow in on tailscale0 to any port 22 proto tcp
# A VPS fica exposta à internet: ela não pode abrir conexão com os outros aparelhos da tailnet.
ufw deny out on tailscale0
ufw enable
```

**Firewall da 443 na própria VM** (cadeia `DOCKER-USER`; o `ufw` não filtra portas do Docker). Os arquivos estão em [`deploy/`](deploy):

```bash
apt install -y ipset
install -d /etc/javai && install -m 644 deploy/cloudflare-ips-v4.txt /etc/javai/cloudflare-ips-v4.txt
install -m 755 deploy/javai-cf-firewall /usr/local/sbin/javai-cf-firewall
install -m 644 deploy/javai-cf-firewall.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now javai-cf-firewall
```

Teste de fora da Cloudflare: `curl -sk -m 8 https://<IP-da-VPS>/` não pode nem conectar. A lista de faixas é a de https://www.cloudflare.com/ips-v4; revise a cada trimestre, junto com a do Caddyfile, e rode `javai-cf-firewall` de novo.

**Firewall do painel da Hostinger** (opcional, segunda camada; hPanel → VPS → Segurança → Firewall):
- uma regra *Accept* TCP 443 para cada faixa de https://www.cloudflare.com/ips (IPv4 e IPv6);
- tudo o mais descartado;
- nenhuma regra para a 22: o SSH entra pela Tailscale.

Confira que a política padrão do firewall é descartar o que não tem regra.

**Atualizações automáticas e swap:**

```bash
apt install -y unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

**Docker:**

```bash
curl -fsSL https://get.docker.com | sh
usermod -aG docker gilvan     # o grupo docker equivale a root: só o seu usuário entra nele
cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "live-restore": true,
  "no-new-privileges": true
}
EOF
systemctl restart docker
```

## 3. Primeiro deploy

1. **Copie esta pasta para a VPS,** pela Tailscale:

   ```bash
   scp -r infra gilvan@<ip-tailscale>:/opt/javai/
   ```

2. **Certificados** em `/opt/javai/infra/caddy/certs/`. O Caddy roda como uid 1000.

   ```bash
   chmod 644 origin.pem cloudflare-origin-pull-ca.pem
   chown 1000:1000 origin.key && chmod 400 origin.key
   ```

3. **Segredos:**

   ```bash
   cd /opt/javai/infra && cp .env.example .env && chmod 600 .env
   openssl rand -base64 32   # uma vez para cada senha; preencha o .env
   ```

   `JAVAI_TAG` é o SHA do commit que o CI publicou (aba Actions do repositório).

4. **Acesso ao GHCR:** as imagens são privadas. Crie um token *classic* só com `read:packages`:

   ```bash
   echo '<token>' | docker login ghcr.io -u gilvan-Borges --password-stdin
   ```

5. **Subir:**

   ```bash
   docker compose pull
   docker compose up -d
   docker compose ps                  # postgres-admin e admin-api "healthy"; admin-migrate "exited (0)"
   docker compose logs admin-migrate  # "Migrações aplicadas"
   ```

## 4. Verificações

| Teste | Esperado |
|---|---|
| Abrir `https://admin.gilvanborges.com.br/admin/swagger-ui` | Tela de login do Access; depois do login, o Swagger |
| `curl -i https://admin.gilvanborges.com.br/admin/v1/profile` sem login | Redireciona para o Access |
| `curl -i https://conteudo.gilvanborges.com.br/v1/snapshot/pt.json` antes de publicar | 404 |
| `curl -i https://conteudo.gilvanborges.com.br/qualquer-coisa` | 404 |
| `curl -vk --connect-timeout 5 https://<IP-público-da-VPS>`, da sua casa | Timeout (firewall do painel). Se conectar, o TLS falha (Authenticated Origin Pulls) |
| `nmap -Pn <IP-público-da-VPS>`, da sua casa | Nenhuma porta aberta |
| `docker stats --no-stream`, na VPS | Anote o consumo e atualize a tabela de memória da spec 01 |

Depois, pelo Swagger:
1. cadastre o perfil;
2. publique o perfil (`PATCH /admin/v1/profile/status`);
3. rode `POST /admin/v1/publish`;
4. abra `https://conteudo.gilvanborges.com.br/v1/snapshot/pt.json`.

## 5. Atualizar

**Pelo CI/CD (o caminho normal).** Um push na `main` roda build, testes, Trivy, publica as imagens e faz o deploy. O script da VPS (`/usr/local/sbin/javai-deploy-root`, cópia em [`deploy/javai-deploy-root`](deploy/javai-deploy-root)) baixa do commit só `docker-compose.yml`, `caddy/Caddyfile` e `postgres-admin/pg_hba.conf`, valida o compose com o `.env` do servidor, sobe os contêineres e **volta sozinho para a versão anterior** (imagens e arquivos) se o `admin-api` não ficar saudável ou o Caddy não ficar de pé. O `.env` e os certificados nunca são tocados.

Se você mudar o próprio script, reinstale na VPS (o CI não consegue, de propósito):

```bash
scp infra/deploy/javai-deploy-root gilvan@<ip-tailscale>:/tmp/
ssh gilvan@<ip-tailscale> 'sudo install -o root -g root -m 755 /tmp/javai-deploy-root /usr/local/sbin/javai-deploy-root'
```

**À mão** (emergência):

```bash
cd /opt/javai/infra
# troque JAVAI_TAG no .env pelo SHA novo
docker compose pull && docker compose up -d
```

O `admin-migrate` roda de novo a cada `up`: aplica migrações novas e reaplica as senhas do `.env`. Para trocar a senha de `admin_owner` ou de `admin_app`, basta mudar o `.env` e subir de novo.

A exceção é `POSTGRES_ADMIN_PASSWORD`, do superusuário: ela só vale na primeira inicialização do banco. Para trocá-la:
1. rode `docker compose exec postgres-admin psql -U postgres -c "\password postgres"`;
2. depois atualize o `.env`.

## 6. Controlei na mesma VPS

A VPS também hospeda o **Controlei** (repositório próprio, finanças familiares), como um segundo projeto Compose em `/opt/controlei`. Os dois dividem só o que precisam dividir:

| Compartilhado | Separado |
|---|---|
| A VPS, o Docker, a Tailscale e o **Caddy** (que ganhou o host `controlei.gilvanborges.com.br` e a rede `controlei-edge`) | Compose, `.env`, redes de dados, bancos e volumes. Nenhum contêiner de um projeto enxerga o outro, exceto o Caddy chegando ao gateway do Controlei |
| O caminho de deploy: OIDC + Tailscale + usuário de comando forçado | O usuário (`deploy-controlei`), a chave e o script (`controlei-deploy-root`): o deploy do Controlei não toca o JavAI e vice-versa |

**Tailscale é o caminho padrão para tudo que é interno:** SSH, deploy do CI e ferramentas de depuração (o Kafka UI do Controlei só existe preso ao IP da Tailscale). O Controlei não publica porta nenhuma.

Para o Caddy alcançar o gateway, a rede externa precisa existir **antes** do próximo deploy do JavAI (senão o `docker compose up` falha e o rollback restaura a versão anterior):

```bash
docker network create --subnet 172.30.50.0/24 controlei-edge
```

O passo a passo completo (usuário de deploy, segredos, DNS, Access, credencial OIDC e primeiro deploy) está em [`deploy/README.md` do repositório do Controlei](https://github.com/gilvan-Borges/controlei/blob/main/deploy/README.md).

Atenção à memória: a VPS tem 7,9 GB. O JavAI usa ~1,8 GB em contêineres e o Controlei tem tetos que somam ~1,7 GB. O `ai-api` já opera perto do próprio teto (507 de 512 MiB em 2026-10-04); confira `docker stats` depois de subir o Controlei.

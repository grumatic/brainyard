# SANDBOX.md — Running Brainyard TUI in OpenShell Sandbox

## Quick Start

```bash
# 1. Create sandbox with policy and project files
openshell sandbox create \
  --name brainyard \
  --policy ./sandbox-policy.yaml \
  --upload .:/sandbox/brainyard

# 2. Connect and set up runtime
openshell sandbox connect brainyard
cd ~/brainyard
bash sandbox-setup.sh          # Install JDK 21, Clojure, Babashka
source ~/.bashrc               # Load PATH/JAVA_HOME

# 3. Configure Maven proxy (required for dependency resolution)
mkdir -p ~/.m2
cat > ~/.m2/settings.xml << 'EOF'
<settings>
  <proxies>
    <proxy>
      <id>sandbox-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>10.200.0.1</host>
      <port>3128</port>
    </proxy>
    <proxy>
      <id>sandbox-http</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>10.200.0.1</host>
      <port>3128</port>
    </proxy>
  </proxies>
</settings>
EOF

# 4. Download dependencies (first run only)
cd ~/brainyard && clojure -P -M:dev

# 5. Upload .env with API keys (from local machine)
openshell sandbox upload brainyard .env /sandbox/brainyard/

# 6. Run TUI
source .env
export CLJ_HTTP_INSECURE=true   # Trust proxy's TLS-terminating cert
export LANG=C.UTF-8 LC_ALL=C.UTF-8  # Fix Unicode rendering
bb tui coact-agent -- openai:gpt-4.1-mini
```

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  OpenShell Sandbox (sandbox user)                   │
│                                                     │
│  bb tui → clojure -M -e '...'  (JVM process)       │
│    └── clj-http → HTTPS → proxy (10.200.0.1:3128)  │
│                              │                      │
│  claude -p  (Node.js)  ─────┘                      │
│                                                     │
│  Installed to ~/.local/:                            │
│    jdk/     — JDK 21 Temurin                        │
│    bin/bb   — Babashka                              │
│    bin/clojure — Clojure CLI                        │
└────────────────────┬────────────────────────────────┘
                     │ TLS-terminating proxy
                     ▼
            LLM APIs (openai, anthropic, etc.)
```

## Key Files

| File | Purpose |
|------|---------|
| `sandbox-policy.yaml` | Network policies (LLM APIs, Maven repos, observability) |
| `sandbox-setup.sh` | User-local JDK/Clojure/Babashka installation (no root) |
| `Dockerfile.sandbox` | BYOC Dockerfile (abandoned — base image too large for push) |
| `.dockerignore` | Excludes everything except Dockerfile for BYOC builds |

## Sandbox Policy

The policy (`sandbox-policy.yaml`) controls which binaries can reach which hosts through the proxy. Key policy zones:

| Policy | Hosts | Binaries |
|--------|-------|----------|
| `brainyard_llm_apis` | api.openai.com, api.anthropic.com, api.groq.com, etc. | java, bb |
| `brainyard_maven` | repo1.maven.org, repo.clojars.org, my.datomic.com | java, clojure, bb |
| `brainyard_sdkman` | api.adoptium.net, download.clojure.org, etc. | curl, bash |
| `brainyard_observability` | us.cloud.langfuse.com | java, bb |
| `brainyard_tavily` | api.tavily.com | java, bb |

### Updating Policy (Hot Reload)

Only `network_policies` can be updated at runtime. Filesystem/process policies require sandbox recreation.

```bash
# Edit sandbox-policy.yaml locally, then:
openshell policy set brainyard --policy ./sandbox-policy.yaml --wait

# Verify
openshell policy list brainyard
```

### Policy Iteration Loop

```bash
# Monitor for denied connections
openshell logs brainyard --tail --source sandbox --level warn

# Pull current policy for editing
openshell policy get brainyard --full > current-policy.yaml

# Edit, then push
openshell policy set brainyard --policy current-policy.yaml --wait
```

## Proxy & SSL

The sandbox proxy at `10.200.0.1:3128` intercepts all outbound HTTPS traffic with TLS termination (`tls: terminate`). This means the proxy decrypts and re-encrypts traffic, presenting its own certificate.

### Java/clj-http (TUI LLM calls)

Two environment variables enable proxy + SSL bypass:

```bash
export CLJ_HTTP_INSECURE=true    # Trust proxy's certificate
# https_proxy is auto-set by sandbox — clj-http reads it via proxy-opts
```

The `llm.clj` module reads `CLJ_HTTP_INSECURE` to set `:insecure? true` on the connection manager, and reads `https_proxy`/`HTTPS_PROXY` to add `:proxy-host`/`:proxy-port` to all `http/post` calls.

### Maven/Clojure dependencies

Maven's Apache HttpClient ignores JVM `-Dhttps.proxyHost` system properties. Use `~/.m2/settings.xml` with explicit proxy config (see Quick Start step 3).

### Node.js (Claude CLI)

Works transparently — Node.js respects `https_proxy` env var and handles proxy TLS automatically.

### Babashka HTTP client

For direct `bb` scripts, use explicit proxy client with trust-all SSL:

```clojure
(import (javax.net.ssl SSLContext TrustManager X509TrustManager)
        (java.security SecureRandom)
        (java.security.cert X509Certificate))

(def trust-all
  (let [tm (reify X509TrustManager
             (checkClientTrusted [_ _ _])
             (checkServerTrusted [_ _ _])
             (getAcceptedIssuers [_] (into-array X509Certificate [])))]
    (doto (SSLContext/getInstance "TLS")
      (.init nil (into-array TrustManager [tm]) (SecureRandom.)))))

(require '[babashka.http-client :as http])
(def client (http/client {:proxy {:host "10.200.0.1" :port 3128}
                          :ssl-context trust-all}))
(http/post "https://api.openai.com/..." {:client client ...})
```

## Provider Selection

| Provider | Status in Sandbox | Notes |
|----------|-------------------|-------|
| `openai:gpt-4.1-mini` | Works | Direct HTTP via clj-http with proxy+SSL fix |
| `anthropic:*` | Works | Same HTTP path |
| `claude-code:*` | Works | Shells out to `claude -p` (Node.js, transparent proxy) |
| `ollama:*` | N/A | No local inference in sandbox |

Default launch: `bb tui coact-agent -- openai:gpt-4.1-mini`

## Uploading Files

```bash
# Upload single file
openshell sandbox upload brainyard ./path/to/file /sandbox/brainyard/path/to/

# Upload .env (may need --no-git-ignore)
openshell sandbox upload brainyard .env /sandbox/brainyard/

# Download from sandbox
openshell sandbox download brainyard /sandbox/brainyard/output ./local-output
```

## Debugging

### Check proxy connectivity

```bash
# From inside sandbox:
curl -v --proxy http://10.200.0.1:3128 https://api.openai.com/v1/models 2>&1 | head -20
```

A `403 Forbidden` on the CONNECT tunnel means the binary isn't in the policy's allowed list.

### Check binary path

```bash
which bb    # /sandbox/.local/bin/bb — must match policy binary entry
which java  # /sandbox/.local/jdk/bin/java
```

### Check environment

```bash
env | grep -i proxy   # Should show https_proxy, HTTP_PROXY, etc.
echo $JAVA_HOME       # /sandbox/.local/jdk
echo $OPENAI_API_KEY  # Verify .env was sourced
```

### View sandbox logs

```bash
# From local machine:
openshell logs brainyard --tail
openshell logs brainyard --tail --source sandbox --level warn
openshell logs brainyard --since 5m
```

### Unicode issues

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8
# Do NOT use en_US.UTF-8 — not installed in sandbox
```

### Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `SunCertPathBuilderException` | Proxy TLS cert not trusted | `export CLJ_HTTP_INSECURE=true` |
| `403 Forbidden` on CONNECT | Binary not in policy | Add binary path to policy, `openshell policy set` |
| `Temporary failure in name resolution` | No proxy config for Maven | Create `~/.m2/settings.xml` with proxy |
| `setlocale: LC_ALL: cannot change locale` | Locale not installed | Use `C.UTF-8` instead of `en_US.UTF-8` |
| Empty LLM responses (claude-code) | `stream-json` needs `--verbose` | Fixed in `claude_code.clj` |
| Process exits on `/model ` | `@providers/providers` deref crash | Fixed in `core.clj` (removed `@`) |

## Sandbox Lifecycle

```bash
# Create
openshell sandbox create --name brainyard --policy ./sandbox-policy.yaml --upload .:/sandbox/brainyard

# Connect (interactive shell)
openshell sandbox connect brainyard

# SSH config (for VS Code Remote-SSH)
openshell sandbox ssh-config brainyard >> ~/.ssh/config

# Inspect
openshell sandbox get brainyard
openshell sandbox list

# Delete
openshell sandbox delete brainyard
```

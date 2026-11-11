package com.devmap.secret;

import java.util.Locale;
import java.util.regex.Pattern;

/** FR-34: files DevMap never opens (env files, keys, credentials), decided by path alone before any read. */
public final class SecretFiles {

  private static final Pattern SECRET = Pattern.compile(
      "(^|.*/)(\\.env(\\..*)?|.*\\.(pem|key|p12|pfx|jks|keystore|crt|cer|der|asc|gpg|ovpn|kdbx)"
          + "|id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?|\\.npmrc|\\.pypirc|\\.netrc|\\.git-credentials|\\.htpasswd"
          + "|credentials(\\..*)?|secrets?(\\..*)?|.*service[-_]account.*\\.json|.*\\.tfvars|.*\\.tfstate(\\..*)?)$");

  /** D-71: env templates (.env.example, .env.infra.sample…) hold names only; still read through the masker. */
  private static final Pattern ENV_TEMPLATE = Pattern.compile("(^|.*/)\\.env(\\.[^/]+)?\\.(example|sample|template|dist)$");

  private SecretFiles() {
  }

  public static boolean isSecret(String path) {
    String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    return !ENV_TEMPLATE.matcher(p).matches() && SECRET.matcher(p).matches();
  }
}

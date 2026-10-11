package com.lifeos.security;

import com.lifeos.cli.LifeAdmin;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Helpers to get a real passkey-signed-in owner browser (CLI link → OTT → register → sign in). */
abstract class OwnerHttpTestBase extends AuthHttpTestBase {

    static final String ORIGIN = "http://localhost:8787"; // life.auth.public-url in `local`

    String ownerLink(String... extra) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] args = new String[extra.length + 1];
        args[0] = "owner-login";
        System.arraycopy(extra, 0, args, 1, extra.length);
        int code = LifeAdmin.run(args, Map.of("SPRING_PROFILES_ACTIVE", "local", "LIFE_DB", DB.toString(),
                        "LIFE_PUBLIC_URL", ORIGIN, "LIFE_KEY_PEPPER", props.pepper()),
                new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        assertThat(code).as(e.toString(StandardCharsets.UTF_8)).isZero();
        return o.toString(StandardCharsets.UTF_8).trim();
    }

    @org.springframework.beans.factory.annotation.Autowired
    AuthProperties props;

    /** A fresh browser signed in with a newly registered passkey (bootstrap or recovery link). */
    OwnerBrowser signedInOwner() throws Exception {
        boolean hasPasskey = count("SELECT COUNT(*) FROM user_credentials") > 0;
        OwnerBrowser setup = new OwnerBrowser(port);
        useLink(setup, hasPasskey ? ownerLink("--recovery") : ownerLink());
        SoftPasskey pk = registerPasskey(setup, "test-" + uniq());
        OwnerBrowser b = new OwnerBrowser(port);
        HttpResponse<String> r = passkeySignIn(b, pk);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return b;
    }

    /** Opens the CLI link and submits the token like the stock submit page does. */
    HttpResponse<String> useLink(OwnerBrowser b, String link) throws Exception {
        String path = link.substring(ORIGIN.length());
        assertThat(b.get(path).statusCode()).isEqualTo(200);
        String token = path.substring(path.indexOf("token=") + 6);
        return b.postForm("/login/ott", "token=" + token + "&_csrf=" + b.csrf);
    }

    SoftPasskey registerPasskey(OwnerBrowser b, String label) throws Exception {
        b.refreshCsrf();
        HttpResponse<String> opts = b.postJson("/webauthn/register/options", null);
        assertThat(opts.statusCode()).as(opts.body()).isEqualTo(200);
        assertThat(opts.body()).contains("\"id\":\"localhost\"");
        SoftPasskey pk = new SoftPasskey(ORIGIN);
        HttpResponse<String> reg = b.postJson("/webauthn/register", pk.register(opts.body(), label));
        assertThat(reg.statusCode()).as(reg.body()).isEqualTo(200);
        assertThat(reg.body()).contains("\"success\":true");
        return pk;
    }

    HttpResponse<String> passkeySignIn(OwnerBrowser b, SoftPasskey pk) throws Exception {
        b.refreshCsrf();
        HttpResponse<String> opts = b.postJson("/webauthn/authenticate/options", null);
        assertThat(opts.statusCode()).as(opts.body()).isEqualTo(200);
        HttpResponse<String> r = b.postJson("/login/webauthn", pk.assertion(opts.body()));
        b.refreshCsrf();
        return r;
    }

}

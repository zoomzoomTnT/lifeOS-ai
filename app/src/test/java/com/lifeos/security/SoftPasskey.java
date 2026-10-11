package com.lifeos.security;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A software WebAuthn authenticator (ES256, "none" attestation) that answers Spring Security's
 * registration and authentication options the way a browser + platform authenticator would.
 */
class SoftPasskey {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ObjectConverter CBOR = new ObjectConverter();
    static final byte UP = 0x01, UV = 0x04, BE = 0x08, BS = 0x10, AT = 0x40;

    final KeyPair keys;
    final byte[] credentialId = new byte[32];
    final String origin;
    String userHandle;
    long signCount;

    /** Same key pair and credential as {@code real}, but reporting a different origin. */
    SoftPasskey(String origin, SoftPasskey real) {
        this.origin = origin;
        this.keys = real.keys;
        System.arraycopy(real.credentialId, 0, credentialId, 0, credentialId.length);
        this.userHandle = real.userHandle;
        this.signCount = real.signCount + 10;
    }

    SoftPasskey(String origin) throws Exception {
        this.origin = origin;
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        keys = g.generateKeyPair();
        new SecureRandom().nextBytes(credentialId);
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    String id() {
        return b64(credentialId);
    }

    byte[] clientData(String type, String challenge) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("challenge", challenge);
        c.put("origin", origin);
        c.put("crossOrigin", false);
        return JSON.writeValueAsBytes(c);
    }

    /** navigator.credentials.create(): body for POST /webauthn/register. */
    String register(String creationOptionsJson, String label) throws Exception {
        JsonNode opt = JSON.readTree(creationOptionsJson);
        String rpId = opt.path("rp").path("id").asString();
        userHandle = opt.path("user").path("id").asString();
        var cose = EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256);
        var authData = new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)), (byte) (UP | UV | BE | BS | AT), 0,
                new AttestedCredentialData(AAGUID.ZERO, credentialId, cose));
        byte[] att = new AttestationObjectConverter(CBOR).convertToBytes(
                new AttestationObject(authData, new NoneAttestationStatement()));
        byte[] cd = clientData("webauthn.create", opt.path("challenge").asString());
        return JSON.writeValueAsString(Map.of("publicKey", Map.of("label", label, "credential", Map.of(
                "id", id(), "rawId", id(), "type", "public-key", "authenticatorAttachment", "platform",
                "clientExtensionResults", Map.of(),
                "response", Map.of("attestationObject", b64(att), "clientDataJSON", b64(cd),
                        "transports", java.util.List.of("internal"))))));
    }

    /** navigator.credentials.get(): body for POST /login/webauthn. */
    String assertion(String requestOptionsJson) throws Exception {
        JsonNode opt = JSON.readTree(requestOptionsJson);
        String rpId = opt.path("rpId").asString();
        var authData = new AuthenticatorData<>(sha256(rpId.getBytes(StandardCharsets.UTF_8)),
                (byte) (UP | UV | BE | BS), ++signCount);
        byte[] ad = new AuthenticatorDataConverter(CBOR).convert(authData);
        byte[] cd = clientData("webauthn.get", opt.path("challenge").asString());
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(ad);
        s.update(sha256(cd));
        return JSON.writeValueAsString(Map.of("id", id(), "rawId", id(), "type", "public-key",
                "authenticatorAttachment", "platform", "clientExtensionResults", Map.of(),
                "response", Map.of("authenticatorData", b64(ad), "clientDataJSON", b64(cd),
                        "signature", b64(s.sign()), "userHandle", userHandle)));
    }
}

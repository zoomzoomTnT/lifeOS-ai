package com.lifeos.security;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.util.List;
import java.util.Map;

/** Stock JDBC passkey store, plus security_events when a passkey is added or removed. */
public class AuditedUserCredentialRepository implements UserCredentialRepository {

    private final UserCredentialRepository delegate;
    private final SecurityEventLog events;

    public AuditedUserCredentialRepository(UserCredentialRepository delegate, SecurityEventLog events) {
        this.delegate = delegate;
        this.events = events;
    }

    @Override
    public void delete(Bytes credentialId) {
        delegate.delete(credentialId);
        events.record(SecurityEventLog.Event.of("passkey_deleted").client(LifePrincipal.OWNER_WEB_CLIENT)
                .credential("passkey").detail(Map.of("credential_id", credentialId.toBase64UrlString())));
    }

    @Override
    public void save(CredentialRecord record) {
        boolean isNew = delegate.findByCredentialId(record.getCredentialId()) == null;
        delegate.save(record);
        if (isNew) {
            events.record(SecurityEventLog.Event.of("passkey_registered").client(LifePrincipal.OWNER_WEB_CLIENT)
                    .credential("passkey").detail(Map.of("label", String.valueOf(record.getLabel()),
                            "credential_id", record.getCredentialId().toBase64UrlString())));
        }
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return delegate.findByCredentialId(credentialId);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return delegate.findByUserId(userId);
    }
}

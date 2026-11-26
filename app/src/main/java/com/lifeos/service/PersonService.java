package com.lifeos.service;

/**
 * People are never created or chosen from request data any more: the acting person comes from the
 * credential ({@link com.lifeos.security.LifePrincipal#personId()}).
 */
public interface PersonService {
    /** people.id of the owner (role = 'owner'). */
    long ownerId();
}

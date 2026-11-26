package com.lifeos.ops;

import com.lifeos.repo.PersonRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No owner row: the wake check is skipped instead of running against a guessed person id. */
class ProactiveCronNoOwnerTest {

    @Test
    void skipsWithoutOwner() {
        PersonRepository people = mock(PersonRepository.class);
        when(people.findOwnerId()).thenReturn(Optional.empty());
        // wake/openclaw/jdbc/events must not be touched: null makes any use fail the test
        var svc = new ProactiveCronService(null, null, null, null, people);
        assertThat(svc.run(true)).containsEntry("wake", false).containsEntry("skipped", "no owner");
    }
}

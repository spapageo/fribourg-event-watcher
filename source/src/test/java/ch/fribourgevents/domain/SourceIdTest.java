package ch.fribourgevents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceIdTest {

  @Test
  void exposesWireNames() {
    assertThat(SourceId.INSITU.wireName()).isEqualTo("insitu");
    assertThat(SourceId.BKA.wireName()).isEqualTo("bka");
    assertThat(SourceId.EVENTFROG.wireName()).isEqualTo("eventfrog");
  }

  @Test
  void parsesWireNameIgnoringCaseAndWhitespace() {
    assertThat(SourceId.fromWireName(" insitu ")).contains(SourceId.INSITU);
    assertThat(SourceId.fromWireName("BKA")).contains(SourceId.BKA);
    assertThat(SourceId.fromWireName("EventFrog")).contains(SourceId.EVENTFROG);
  }

  @Test
  void returnsEmptyForUnknownOrMissingWireName() {
    assertThat(SourceId.fromWireName(null)).isEmpty();
    assertThat(SourceId.fromWireName("")).isEmpty();
    assertThat(SourceId.fromWireName("localcities")).isEmpty();
  }
}

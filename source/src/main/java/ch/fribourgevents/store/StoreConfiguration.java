package ch.fribourgevents.store;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {

  @Bean
  public ZoneId digestZone(@Value("${digest.zone:Europe/Zurich}") String zone) {
    return ZoneId.of(zone);
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}

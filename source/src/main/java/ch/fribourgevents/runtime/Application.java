package ch.fribourgevents.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "ch.fribourgevents")
@EntityScan("ch.fribourgevents.store")
@EnableJpaRepositories("ch.fribourgevents.store")
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}

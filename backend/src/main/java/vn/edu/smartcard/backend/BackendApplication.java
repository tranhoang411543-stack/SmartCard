package vn.edu.smartcard.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class BackendApplication {
  public static void main(String[] args) throws Exception {
    if (java.util.Arrays.asList(args).contains("--create-demo-cards")) {
      DemoCards.create(new LocalConfig());
      return;
    }
    if (java.util.Arrays.asList(args).contains("--create-demo-cards-0011-0060")) {
      DemoBatchCards.create(new LocalConfig());
      return;
    }
    if (java.util.Arrays.asList(args).contains("--check-integrations")) {
      IntegrationProbe.run(new LocalConfig());
      return;
    }
    if (java.util.Arrays.asList(args).contains("--check-card-runtime")) {
      RuntimeProbe.run(new LocalConfig());
      return;
    }
    SpringApplication.run(BackendApplication.class, args);
  }
}

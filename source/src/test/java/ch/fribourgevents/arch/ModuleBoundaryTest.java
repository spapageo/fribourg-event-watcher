package ch.fribourgevents.arch;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the module graph from {@code docs/SPEC.md} §0 and {@code docs/architecture.md}.
 *
 * <p>Only dependencies between the project's own layers are considered, so a layer may use any
 * third-party library (Spring, JPA, Selenium, Jsoup) without tripping the rule. Dependencies inside
 * one layer (for example {@code source.eventfrog} on the {@code source} SPI) are allowed by
 * ArchUnit.
 */
@AnalyzeClasses(packages = "ch.fribourgevents")
class ModuleBoundaryTest {

  private static final String DOMAIN = "Domain";
  private static final String STORE = "Store";
  private static final String DEDUP = "Dedup";
  private static final String DIGEST = "Digest";
  private static final String NOTIFY = "Notify";
  private static final String SOURCE = "Source";
  private static final String PIPELINE = "Pipeline";
  private static final String RUNTIME = "Runtime";

  @ArchTest
  static final ArchRule modules_only_depend_downwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer(DOMAIN)
          .definedBy("ch.fribourgevents.domain..")
          .layer(STORE)
          .definedBy("ch.fribourgevents.store..")
          .layer(DEDUP)
          .definedBy("ch.fribourgevents.dedup..")
          .layer(DIGEST)
          .definedBy("ch.fribourgevents.digest..")
          .layer(NOTIFY)
          .definedBy("ch.fribourgevents.notify..")
          .layer(SOURCE)
          .definedBy("ch.fribourgevents.source..")
          .layer(PIPELINE)
          .definedBy("ch.fribourgevents.pipeline..")
          .layer(RUNTIME)
          .definedBy("ch.fribourgevents.runtime..")
          .whereLayer(RUNTIME)
          .mayNotBeAccessedByAnyLayer()
          .whereLayer(PIPELINE)
          .mayOnlyBeAccessedByLayers(RUNTIME)
          .whereLayer(DEDUP)
          .mayOnlyBeAccessedByLayers(PIPELINE, RUNTIME)
          .whereLayer(DIGEST)
          .mayOnlyBeAccessedByLayers(NOTIFY, PIPELINE, RUNTIME)
          .whereLayer(NOTIFY)
          .mayOnlyBeAccessedByLayers(PIPELINE, RUNTIME)
          .whereLayer(SOURCE)
          .mayOnlyBeAccessedByLayers(PIPELINE, RUNTIME)
          .whereLayer(STORE)
          .mayOnlyBeAccessedByLayers(DEDUP, DIGEST, PIPELINE, RUNTIME)
          .whereLayer(DOMAIN)
          .mayOnlyBeAccessedByLayers(DEDUP, DIGEST, NOTIFY, SOURCE, STORE, PIPELINE, RUNTIME);
}

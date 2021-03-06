// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkState;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Holds specialized JUnit rules that start a test database server and provide {@link
 * JpaTransactionManager} instances.
 */
public class JpaTestRules {
  private static final String GOLDEN_SCHEMA_SQL_PATH = "sql/schema/nomulus.golden.sql";

  /**
   * Junit rule for integration tests with JPA framework, when the underlying database is populated
   * with the Nomulus Cloud SQL schema.
   */
  public static class JpaIntegrationTestRule extends JpaTransactionManagerRule {

    private JpaIntegrationTestRule(
        Clock clock,
        ImmutableList<Class> extraEntityClasses,
        ImmutableMap<String, String> userProperties) {
      super(clock, Optional.of(GOLDEN_SCHEMA_SQL_PATH), extraEntityClasses, userProperties);
    }
  }

  /**
   * Junit rule for unit tests with JPA framework, when the underlying database is populated by the
   * optional init script (which must not be the Nomulus Cloud SQL schema).
   */
  public static class JpaUnitTestRule extends JpaTransactionManagerRule {

    private JpaUnitTestRule(
        Clock clock,
        Optional<String> initScriptPath,
        ImmutableList<Class> extraEntityClasses,
        ImmutableMap<String, String> userProperties) {
      super(clock, initScriptPath, extraEntityClasses, userProperties);
    }
  }

  /**
   * Junit rule for member classes of {@link
   * google.registry.schema.integration.SqlIntegrationTestSuite}. In addition to providing a
   * database through {@link JpaIntegrationTestRule}, it also keeps track of the test coverage of
   * the declare JPA entities (in persistence.xml).
   *
   * <p>It is enforced through tests that all test classes using this rule must be included in the
   * {@code SqlIntegrationTestSuite}. For the sake of efficiency, end-to-end tests that mainly test
   * non-database functionalities should not use this rule.
   */
  public static final class JpaIntegrationWithCoverageRule implements TestRule {
    private final RuleChain ruleChain;

    JpaIntegrationWithCoverageRule(JpaIntegrationTestRule integrationTestRule) {
      TestCaseWatcher watcher = new TestCaseWatcher();
      this.ruleChain =
          RuleChain.outerRule(watcher)
              .around(integrationTestRule)
              .around(new JpaEntityCoverage(watcher));
    }

    @Override
    public Statement apply(Statement base, Description description) {
      return ruleChain.apply(base, description);
    }
  }

  /** Builder of test rules that provide {@link JpaTransactionManager}. */
  public static class Builder {
    private String initScript;
    private Clock clock;
    private List<Class> extraEntityClasses = new ArrayList<Class>();
    private Map<String, String> userProperties = new HashMap<String, String>();

    /**
     * Sets the SQL script to be used to initialize the database. If not set,
     * sql/schema/nomulus.golden.sql will be used.
     *
     * <p>The {@code initScript} is only accepted when building {@link JpaUnitTestRule}.
     */
    public Builder withInitScript(String initScript) {
      this.initScript = initScript;
      return this;
    }

    public Builder withClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /** Adds annotated class(es) to the known entities for the database. */
    public Builder withEntityClass(Class... classes) {
      this.extraEntityClasses.addAll(ImmutableSet.copyOf(classes));
      return this;
    }

    /** Adds the specified property to those used to initialize the transaction manager. */
    public Builder withProperty(String name, String value) {
      this.userProperties.put(name, value);
      return this;
    }

    /** Builds a {@link JpaIntegrationTestRule} instance. */
    public JpaIntegrationTestRule buildIntegrationTestRule() {
      return new JpaIntegrationTestRule(
          clock == null ? new FakeClock(DateTime.now(UTC)) : clock,
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }

    /**
     * Builds a {@link RuleChain} around {@link JpaIntegrationTestRule} that also checks test
     * coverage of JPA entity classes.
     */
    public JpaIntegrationWithCoverageRule buildIntegrationWithCoverageRule() {
      checkState(initScript == null, "Integration tests do not accept initScript");
      return new JpaIntegrationWithCoverageRule(buildIntegrationTestRule());
    }

    /** Builds a {@link JpaUnitTestRule} instance. */
    public JpaUnitTestRule buildUnitTestRule() {
      checkState(
          !Objects.equals(GOLDEN_SCHEMA_SQL_PATH, initScript),
          "Unit tests must not depend on the Nomulus schema.");
      return new JpaUnitTestRule(
          clock == null ? new FakeClock(DateTime.now(UTC)) : clock,
          Optional.ofNullable(initScript),
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }
  }
}

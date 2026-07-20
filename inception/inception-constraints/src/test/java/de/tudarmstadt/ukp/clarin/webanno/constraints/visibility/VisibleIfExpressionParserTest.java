/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.constraints.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class VisibleIfExpressionParserTest
{
    private boolean eval(String aExpression, Map<String, String> aValues)
        throws VisibleIfSyntaxException
    {
        Function<String, String> lookup = aValues::get;
        return VisibleIfExpressionParser.parse(aExpression).evaluate(lookup);
    }

    @Test
    void thatEqualityWorks() throws Exception
    {
        var values = Map.of("entityType", "PERSON");
        assertThat(eval("entityType == \"PERSON\"", values)).isTrue();
        assertThat(eval("entityType == \"ORG\"", values)).isFalse();
    }

    @Test
    void thatInequalityWorks() throws Exception
    {
        var values = Map.of("entityType", "PERSON");
        assertThat(eval("entityType != \"PERSON\"", values)).isFalse();
        assertThat(eval("entityType != \"ORG\"", values)).isTrue();
    }

    @Test
    void thatInWorks() throws Exception
    {
        var values = Map.of("entityType", "PERSON");
        assertThat(eval("entityType in [\"PERSON\", \"ORG\"]", values)).isTrue();
        assertThat(eval("entityType in [\"ORG\", \"LOC\"]", values)).isFalse();
        assertThat(eval("entityType in []", values)).isFalse();
    }

    @Test
    void thatNotInWorks() throws Exception
    {
        var values = Map.of("entityType", "PERSON");
        assertThat(eval("entityType not in [\"PERSON\", \"ORG\"]", values)).isFalse();
        assertThat(eval("entityType not in [\"ORG\", \"LOC\"]", values)).isTrue();
    }

    @Test
    void thatAndWorks() throws Exception
    {
        var values = Map.of("entityType", "PERSON", "certainty", "HIGH");
        assertThat(eval("entityType == \"PERSON\" && certainty == \"HIGH\"", values)).isTrue();
        assertThat(eval("entityType == \"PERSON\" && certainty == \"LOW\"", values)).isFalse();
    }

    @Test
    void thatOrWorks() throws Exception
    {
        var values = Map.of("entityType", "EVENT", "certainty", "LOW");
        assertThat(eval("entityType == \"ORG\" || certainty == \"LOW\"", values)).isTrue();
        assertThat(eval("entityType == \"ORG\" || certainty == \"HIGH\"", values)).isFalse();
    }

    @Test
    void thatNotWorks() throws Exception
    {
        var values = Map.of("polarity", "NEG");
        assertThat(eval("!(polarity == \"NEG\")", values)).isFalse();
        assertThat(eval("polarity != \"NEG\"", values)).isFalse();
    }

    @Test
    void thatNestedParenthesesWork() throws Exception
    {
        var values = Map.of("entityType", "PERSON", "certainty", "LOW", "polarity", "POS");
        assertThat(eval("(entityType == \"PERSON\" && certainty == \"LOW\") || polarity == \"NEG\"",
                values)).isTrue();
        assertThat(
                eval("entityType == \"PERSON\" && (certainty == \"HIGH\" || polarity == \"NEG\")",
                        values)).isFalse();
    }

    @Test
    void thatMissingFeatureIsNull() throws Exception
    {
        var values = new HashMap<String, String>();
        assertThat(eval("entityType == \"PERSON\"", values)).isFalse();
        assertThat(eval("entityType != \"PERSON\"", values)).isTrue();
        assertThat(eval("entityType in [\"PERSON\"]", values)).isFalse();
        assertThat(eval("entityType not in [\"PERSON\"]", values)).isTrue();
    }

    @Test
    void thatInvalidSyntaxThrows()
    {
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse("entityType ="));
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse("entityType == \"PERSON\" &&"));
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse("(entityType == \"PERSON\""));
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse("entityType == \"unterminated"));
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse("123abc == \"x\""));
    }

    @Test
    void thatBlankExpressionIsRejectedByParserDirectly()
    {
        // The parser itself requires a well-formed expression; treating blank as "always visible"
        // is the responsibility of VisibleIfEvaluator, not the parser.
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfExpressionParser.parse(""));
    }
}

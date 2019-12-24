/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.spelling;

import java.util.Collection;
import java.util.Map;

import org.apache.lucene.util.LuceneTestCase.SuppressTempFileChecks;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.SpellingParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.SpellCheckComponent;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Simple tests for {@link DirectSolrSpellChecker}
 */
@SuppressTempFileChecks(bugUrl = "https://issues.apache.org/jira/browse/SOLR-1877 Spellcheck IndexReader leak bug?")
public class DirectSolrSpellCheckerTest extends SolrTestCaseJ4 {

  private static SpellingQueryConverter queryConverter;

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig-spellcheckcomponent.xml","schema.xml");
    //Index something with a title
    assertNull(h.validateUpdate(adoc("id", "0", "teststop", "This is a title")));
    assertNull(h.validateUpdate(adoc("id", "1", "teststop", "The quick reb fox jumped over the lazy brown dogs.")));
    assertNull(h.validateUpdate(adoc("id", "2", "teststop", "This is a Solr")));
    assertNull(h.validateUpdate(adoc("id", "3", "teststop", "solr foo")));
    assertNull(h.validateUpdate(adoc("id", "4", "teststop", "another foo")));
    assertNull(h.validateUpdate(commit()));
    queryConverter = new SimpleQueryConverter();
    queryConverter.init(new NamedList());
  }

  @Test
  public void testCorrectableSpelling() throws Exception {
    String incorrectSpelling = "fob";
    String correctSpelling = "foo";
    Map<String, Integer> suggestions = getSuggestionsForFirstToken(incorrectSpelling, 2, null);
    Map.Entry<String, Integer> firstSuggestion = suggestions.entrySet().iterator().next();
    String firstSpelling = firstSuggestion.getKey();
    Integer firstFrequency = firstSuggestion.getValue();
    assertEquals("'" + firstSpelling + "' is not equal to '" + correctSpelling + "'", firstSpelling, correctSpelling);
    assertFalse("'" + firstFrequency + "' equals: " + SpellingResult.NO_FREQUENCY_INFO, firstFrequency == SpellingResult.NO_FREQUENCY_INFO);
  }

  @Test
  public void testUncorrectableSpelling() throws Exception {
    String query = "super";
    Map<String, Integer> suggestions = getSuggestionsForFirstToken(query, 2, null);
    assertTrue("suggestions should be empty", suggestions.isEmpty());
  }

  @Test
  public void testAboveMaxLength() throws Exception {
    String query = "anothar";
    Map<String, Integer> suggestionFrequencies = getSuggestionsForFirstToken(query, 2, 4);
    assertTrue("Misspelled term '" + query + "' should not get suggestions if MAXQUERYLENGTH < term length", suggestionFrequencies.isEmpty());
  }

  @Test
  public void testAtMaxLength() throws Exception {
    String query = "anothar";
    Map<String, Integer> suggestionFrequencies = getSuggestionsForFirstToken(query, 2, 7);
    assertTrue("Misspelled term '" + query + "' with termLength == maxQueryLength should get exactly one suggestion", suggestionFrequencies.size() == 1);
    String firstSuggestion = suggestionFrequencies.keySet().iterator().next();
    assertEquals("'" + firstSuggestion + "' is not equal to 'another'", firstSuggestion, "another");
  }

  @Test
  public void testBelowMaxLength() throws Exception {
    Map<String, Integer> suggestionFrequencies = getSuggestionsForFirstToken("anothar", 2, 8);
    assertTrue("Misspelled term with termLength < maxQueryLength should get exactly one firstSuggestion", suggestionFrequencies.size() == 1);
    String firstSuggestion = suggestionFrequencies.keySet().iterator().next();
    assertEquals("'" + firstSuggestion + "' is not equal to 'another'", firstSuggestion, "another");
  }

  @Test
  public void testOnlyMorePopularWithExtendedResults() throws Exception {
    assertQ(req("q", "teststop:fox", "qt", "/spellCheckCompRH", SpellCheckComponent.COMPONENT_NAME, "true", SpellingParams.SPELLCHECK_DICT, "direct", SpellingParams.SPELLCHECK_EXTENDED_RESULTS, "true", SpellingParams.SPELLCHECK_ONLY_MORE_POPULAR, "true"),
        "//lst[@name='spellcheck']/lst[@name='suggestions']/lst[@name='fox']/int[@name='origFreq']=1",
        "//lst[@name='spellcheck']/lst[@name='suggestions']/lst[@name='fox']/arr[@name='suggestion']/lst/str[@name='word']='foo'",
        "//lst[@name='spellcheck']/lst[@name='suggestions']/lst[@name='fox']/arr[@name='suggestion']/lst/int[@name='freq']=2",
        "//lst[@name='spellcheck']/bool[@name='correctlySpelled']='true'"
    );
  }


  private Map<String,Integer> getSuggestionsForFirstToken(String query, Integer minQueryLength, Integer maxQueryLength) throws Exception{
    Collection<Token> queryTokens = queryConverter.convert(query);
    SpellingResult spellingResult = getCorpusSpellingSuggestions(queryTokens, minQueryLength, maxQueryLength);

    Token firstToken = queryTokens.iterator().next();
    Map<String,Integer> firstTokenSuggestions = spellingResult.get(firstToken);
    assertNotNull("firstTokenSuggestions collection should never be null", firstTokenSuggestions);
    return firstTokenSuggestions;
  }

  private SpellingResult getCorpusSpellingSuggestions(Collection<Token> tokens, Integer minQueryLength, Integer maxQueryLength) throws Exception{
    DirectSolrSpellChecker checker = new DirectSolrSpellChecker();
    NamedList checkerConfig = new NamedList<>();
    checkerConfig.add("classname", DirectSolrSpellChecker.class.getName());
    checkerConfig.add(SolrSpellChecker.FIELD, "teststop");
    if(minQueryLength != null) checkerConfig.add(DirectSolrSpellChecker.MINQUERYLENGTH, minQueryLength);
    if(maxQueryLength != null) checkerConfig.add(DirectSolrSpellChecker.MAXQUERYLENGTH, maxQueryLength);
    SolrCore core = h.getCore();
    checker.init(checkerConfig, core);
    return h.getCore().withSearcher(searcher -> {
      SpellingOptions spellOpts = new SpellingOptions(tokens, searcher.getIndexReader());
      return checker.getSuggestions(spellOpts);
    });
  }

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.joshua.decoder;

import static org.apache.joshua.decoder.StructuredTranslationFactory.fromViterbiDerivation;
import static org.apache.joshua.decoder.ff.FeatureMap.hashFeature;
import static org.apache.joshua.decoder.hypergraph.ViterbiExtractor.getViterbiFeatures;
import static org.apache.joshua.decoder.hypergraph.ViterbiExtractor.getViterbiString;
import static org.apache.joshua.decoder.hypergraph.ViterbiExtractor.getViterbiWordAlignments;
import static org.apache.joshua.util.FormatUtils.removeSentenceMarkers;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import org.apache.joshua.decoder.ff.FeatureVector;
import org.apache.joshua.decoder.hypergraph.HyperGraph;
import org.apache.joshua.decoder.hypergraph.KBestExtractor;
import org.apache.joshua.decoder.io.DeNormalize;
import org.apache.joshua.decoder.segment_file.Sentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents translated input objects (sentences or lattices). It is aware of the source
 * sentence and id and contains the decoded hypergraph. Translation objects are returned by
 * DecoderTask instances to the InputHandler, where they are assembled in order for output.
 *
 * @author Matt Post post@cs.jhu.edu
 * @author Felix Hieber fhieber@amazon.com
 */

public class Translation {
  private static final Logger LOG = LoggerFactory.getLogger(Translation.class);
  private final Sentence sourceSentence;

  /**
   * This stores the output of the translation so we don't have to hold onto the hypergraph while we
   * wait for the outputs to be assembled.
   */
  private String output = null;

  /**
   * Stores the list of StructuredTranslations.
   * If joshuaConfig.topN == 0, will only contain the Viterbi translation.
   * Else it will use KBestExtractor to populate this list.
   */
  private List<StructuredTranslation> structuredTranslations = null;

  public Translation(final Sentence sourceSentence,
      final HyperGraph hypergraph, DecoderConfig config) {
    this.sourceSentence = sourceSentence;
    final boolean useStructuredOutput = sourceSentence.getFlags().getBoolean("use_structured_output");
    final int topN = sourceSentence.getFlags().getInt("top_n");
    final String outputFormat = sourceSentence.getFlags().getString("output_format");
    final boolean rescoreForest = sourceSentence.getFlags().getBoolean("rescore_forest");
    final double rescoreForestWeight = sourceSentence.getFlags().getDouble("rescore_forest_weight");

    /**
     * Structured output from Joshua provides a way to programmatically access translation results
     * from downstream applications, instead of writing results as strings to an output buffer.
     */
    if (useStructuredOutput) {

      if (topN == 0) {
        /*
         * Obtain Viterbi StructuredTranslation
         */
        StructuredTranslation translation = fromViterbiDerivation(sourceSentence, hypergraph, config.getFeatureFunctions());
        this.output = translation.getTranslationString();
        structuredTranslations = Collections.singletonList(translation);

      } else {
        /*
         * Get K-Best list of StructuredTranslations
         */
        final KBestExtractor kBestExtractor = new KBestExtractor(sourceSentence, config, false);
        structuredTranslations = kBestExtractor.KbestExtractOnHG(hypergraph, topN);
        if (structuredTranslations.isEmpty()) {
            structuredTranslations = Collections
                .singletonList(StructuredTranslationFactory.fromEmptyOutput(sourceSentence));
            this.output = "";
        } else {
            this.output = structuredTranslations.get(0).getTranslationString();
        }
        // TODO: We omit the BLEU rescoring for now since it is not clear whether it works at all and what the desired output is below.
      }

    } else {

      StringWriter sw = new StringWriter();
      BufferedWriter out = new BufferedWriter(sw);

      try {

        if (hypergraph != null) {

          long startTime = System.currentTimeMillis();

          if (topN == 0) {

            /* construct Viterbi output */
            final String best = getViterbiString(hypergraph);

            LOG.info("Translation {}: {} {}", sourceSentence.id(), hypergraph.goalNode.getScore(), best);

            /*
             * Setting topN to 0 turns off k-best extraction, in which case we need to parse through
             * the output-string, with the understanding that we can only substitute variables for the
             * output string, sentence number, and model score.
             */
            String translation = outputFormat
                .replace("%s", removeSentenceMarkers(best))
                .replace("%S", DeNormalize.processSingleLine(best))
                .replace("%c", String.format("%.3f", hypergraph.goalNode.getScore()))
                .replace("%i", String.format("%d", sourceSentence.id()));

            if (outputFormat.contains("%a")) {
              translation = translation.replace("%a", getViterbiWordAlignments(hypergraph));
            }

            if (outputFormat.contains("%f")) {
              final FeatureVector features = getViterbiFeatures(hypergraph, config.getFeatureFunctions(), sourceSentence);
              translation = translation.replace("%f", features.textFormat());
            }

            out.write(translation);
            out.newLine();

          } else {

            final KBestExtractor kBestExtractor = new KBestExtractor(
                sourceSentence, config, false);
            kBestExtractor.lazyKBestExtractOnHG(hypergraph, topN, out);

            if (rescoreForest) {
              final int bleuFeatureHash = hashFeature("BLEU");
              // TODO(fhieber): this is fishy, why would we want to change the decoder weights HERE?
              config.getWeights().add(bleuFeatureHash, (float) rescoreForestWeight);
              kBestExtractor.lazyKBestExtractOnHG(hypergraph, topN, out);

              config.getWeights().add(bleuFeatureHash, -(float) rescoreForestWeight);
              kBestExtractor.lazyKBestExtractOnHG(hypergraph, topN, out);
            }
          }

          float seconds = (System.currentTimeMillis() - startTime) / 1000.0f;
          LOG.info("Input {}: {}-best extraction took {} seconds", id(), topN, seconds);

        } else {

          // Failed translations and blank lines get empty formatted outputs
          out.write(getFailedTranslationOutput(sourceSentence, outputFormat));
          out.newLine();

        }

        out.flush();

      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      this.output = sw.toString();

    }

    // Force any StateMinimizingLanguageModel pool mappings to be cleaned
    sourceSentence.getStateManager().clearStatePool();

  }

  public Sentence getSourceSentence() {
    return this.sourceSentence;
  }

  public int id() {
    return sourceSentence.id();
  }

  @Override
  public String toString() {
    return output;
  }
  
  private String getFailedTranslationOutput(final Sentence source, final String outputFormat) {
    return outputFormat
        .replace("%s", source.source())
        .replace("%e", "")
        .replace("%S", "")
        .replace("%t", "()")
        .replace("%i", Integer.toString(source.id()))
        .replace("%f", "")
        .replace("%c", "0.000");
  }

  /**
   * Returns the StructuredTranslations
   * if JoshuaConfiguration.use_structured_output == True.
   * @throws RuntimeException if JoshuaConfiguration.use_structured_output == False.
   * @return List of StructuredTranslations.
   */
  public List<StructuredTranslation> getStructuredTranslations() {
    if (structuredTranslations == null) {
      throw new RuntimeException(
          "No StructuredTranslation objects created. You should set JoshuaConfigration.use_structured_output = true");
    }
    return structuredTranslations;
  }
}
/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.teleport.templates;

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.teleport.io.DynamicJdbcIO;
import com.google.cloud.teleport.templates.common.JdbcConverters;
import com.google.cloud.teleport.util.KMSEncryptedNestedValueProvider;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;

/**
 * A template that copies data from a relational database using JDBC to an existing BigQuery table.
 */
public class JdbcToBigQuery {

  private static ValueProvider<String> maybeDecrypt(
      ValueProvider<String> unencryptedValue, ValueProvider<String> kmsKey) {
    return new KMSEncryptedNestedValueProvider(unencryptedValue, kmsKey);
  }

  /**
   * Main entry point for executing the pipeline. This will run the pipeline asynchronously. If
   * blocking execution is required, use the {@link
   * JdbcToBigQuery#run(JdbcConverters.JdbcToBigQueryOptions)} method to start the pipeline and
   * invoke {@code result.waitUntilFinish()} on the {@link PipelineResult}
   *
   * @param args The command-line arguments to the pipeline.
   */
  public static void main(String[] args) {

    // Parse the user options passed from the command-line
    JdbcConverters.JdbcToBigQueryOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(JdbcConverters.JdbcToBigQueryOptions.class);

    run(options);
  }

  /**
   * Runs the pipeline with the supplied options.
   *
   * @param options The execution parameters to the pipeline.
   * @return The result of the pipeline execution.
   */
  private static PipelineResult run(JdbcConverters.JdbcToBigQueryOptions options) {
    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);

    /*
     * Steps: 1) Read records via JDBC and convert to TableRow via RowMapper
     *        2) Append TableRow to BigQuery via BigQueryIO
     */

    
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

    List<BigQuerySchema> bigQuerySchemas = mapper.readValue(
            new File(Resources.getResource("schema/bigquery/app_schema.yaml").getPath()),
            new TypeReference<List<BigQuerySchema>>(){}
    );

    bigQuerySchemas.stream().forEach(schema -> {
      pipeline
              .apply(
                      "Read '" +  schema.getQuery() + "' from JdbcIO",
                      DynamicJdbcIO.<TableRow>read()
                              .withDataSourceConfiguration(
                                      DynamicJdbcIO.DynamicDataSourceConfiguration.create(
                                              ValueProvider.StaticValueProvider.of("org.postgresql.Driver"),
                                              options.getConnectionURL())
                                              .withUsername(options.getUsername())
                                              .withPassword(options.getPassword())
                                              .withConnectionProperties(options.getConnectionProperties()))
                              .withQuery(ValueProvider.StaticValueProvider.of(schema.getQuery()))
                              .withCoder(TableRowJsonCoder.of())
                              .withRowMapper(JdbcConverters.getResultSetToTableRow()))
              .apply(
                      "Write to BigQuery: " + schema.getOutputTableName(),
                      BigQueryIO.writeTableRows()
                              .withoutValidation()
                              .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED) // テーブルがなければ作成する
                              .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_TRUNCATE) // 既存のテーブルを消して書き込む
                              .withSchema(schema.getTableSchema())
                              .ignoreUnknownValues()
                              .withCustomGcsTempLocation(options.getBigQueryLoadingTemporaryDirectory())
                              .to(new CustomTableValueProvider(schema.getOutputTableName(), options.getOutputDataset())));
    });

    // Execute the pipeline and return the result.
    return pipeline.run();


  
  }
}

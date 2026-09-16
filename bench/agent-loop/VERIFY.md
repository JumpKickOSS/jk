# Scenario verification

Date: 2026-09-16 · jk 0.13.7 · tools: jk, mvn, gradle · last run 3m 26s

Every (repo × failure) is injected into a fresh copy of the tool's green baseline, the tool is run (`jk test`, `mvn-results test`, `gradle-results test`), and the resulting `target/jk-results.md` must (a) come from a non-zero exit, (b) name the injected failure — the edited file and line for a compile failure, the failing test class for a test failure — and (c) name nothing else. The tree is then reverted and checked clean, and after the last failure the tool runs once more and must be green.

**165 / 165 scenario runs verified**, 51 / 51 sandboxes green after revert.

| Repo | Failure | Tool | Exit | Wall | Verified | Results named |
|---|---|---|---|---|---|---|
| gs-rest-service | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | compile-error | gradle | 1 | 0.8s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | failing-assertion | jk | 4 | 2.2s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | failing-assertion | mvn | 1 | 3.2s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | failing-assertion | gradle | 1 | 2.3s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | missing-dependency | mvn | 1 | 1.4s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | version-conflict | jk | 1 | 0.4s | yes | `parse-build`: ‼ Cannot resolve dependencies: |
| gs-rest-service | version-conflict | mvn | 1 | 2.1s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | version-conflict | gradle | 1 | 1.2s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-rest-service | green-after-revert | mvn | 0 | 3.2s | yes |  |
| gs-rest-service | green-after-revert | gradle | 0 | 2.2s | yes |  |
| gs-spring-boot | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | compile-error | mvn | 1 | 1.4s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | compile-error | gradle | 1 | 0.6s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | failing-assertion | jk | 4 | 3.5s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | failing-assertion | mvn | 1 | 4.1s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | failing-assertion | gradle | 1 | 3.3s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | missing-dependency | mvn | 1 | 1.5s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | version-conflict | jk | 1 | 0.4s | yes | `parse-build`: ‼ Cannot resolve dependencies: |
| gs-spring-boot | version-conflict | mvn | 1 | 2.2s | yes | com.example.springboot.HelloControllerIntegrationTest, com.example.springboot.HelloControllerTest |
| gs-spring-boot | version-conflict | gradle | 1 | 1.2s | yes | com.example.springboot.HelloControllerIntegrationTest, com.example.springboot.HelloControllerTest |
| gs-spring-boot | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-spring-boot | green-after-revert | mvn | 0 | 4.2s | yes |  |
| gs-spring-boot | green-after-revert | gradle | 0 | 3.1s | yes |  |
| gs-accessing-data-jpa | compile-error | jk | 1 | 0.9s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | compile-error | mvn | 1 | 1.4s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | compile-error | gradle | 1 | 0.6s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | failing-assertion | jk | 4 | 2.9s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | failing-assertion | mvn | 1 | 3.8s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | failing-assertion | gradle | 1 | 3.1s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | missing-dependency | mvn | 1 | 1.5s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-accessing-data-jpa | green-after-revert | mvn | 0 | 4.1s | yes |  |
| gs-accessing-data-jpa | green-after-revert | gradle | 0 | 3.2s | yes |  |
| gs-accessing-data-r2dbc | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | compile-error | gradle | 1 | 0.8s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | failing-assertion | jk | 4 | 2.2s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | failing-assertion | mvn | 1 | 3.3s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | failing-assertion | gradle | 1 | 2.4s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-dependency | mvn | 1 | 1.4s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-resource | jk | 4 | 1.8s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-resource | mvn | 1 | 3.1s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-resource | gradle | 1 | 2.2s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-accessing-data-r2dbc | green-after-revert | mvn | 0 | 3.5s | yes |  |
| gs-accessing-data-r2dbc | green-after-revert | gradle | 0 | 2.3s | yes |  |
| gs-accessing-data-rest | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | compile-error | mvn | 1 | 1.4s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | failing-assertion | jk | 4 | 3.7s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | failing-assertion | mvn | 1 | 4.6s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | failing-assertion | gradle | 1 | 3.9s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | missing-dependency | mvn | 1 | 1.6s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-accessing-data-rest | green-after-revert | mvn | 0 | 5.0s | yes |  |
| gs-accessing-data-rest | green-after-revert | gradle | 0 | 3.9s | yes |  |
| gs-actuator-service | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | compile-error | gradle | 1 | 0.5s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | failing-assertion | jk | 4 | 4.0s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | failing-assertion | mvn | 1 | 3.8s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | failing-assertion | gradle | 1 | 3.2s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | missing-dependency | mvn | 1 | 1.5s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-actuator-service | green-after-revert | mvn | 0 | 4.2s | yes |  |
| gs-actuator-service | green-after-revert | gradle | 0 | 3.0s | yes |  |
| gs-batch-processing | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | failing-assertion | jk | 4 | 1.8s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | failing-assertion | mvn | 1 | 2.9s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | failing-assertion | gradle | 1 | 2.2s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/batchprocessing/BatchConfiguration.java:5 |
| gs-batch-processing | missing-dependency | mvn | 1 | 1.4s | yes | src/main/java/com/example/batchprocessing/BatchConfiguration.java:5 |
| gs-batch-processing | missing-dependency | gradle | 1 | 0.8s | yes | src/main/java/com/example/batchprocessing/BatchConfiguration.java:5 |
| gs-batch-processing | missing-resource | jk | 4 | 1.8s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-resource | mvn | 1 | 3.0s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-resource | gradle | 1 | 2.1s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-batch-processing | green-after-revert | mvn | 0 | 3.0s | yes |  |
| gs-batch-processing | green-after-revert | gradle | 0 | 2.0s | yes |  |
| gs-consuming-rest | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | missing-dependency | jk | 1 | 0.7s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:3 |
| gs-consuming-rest | missing-dependency | mvn | 1 | 1.4s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:3 |
| gs-consuming-rest | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:3 |
| gs-consuming-rest | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-consuming-rest | green-after-revert | mvn | 0 | 3.0s | yes |  |
| gs-consuming-rest | green-after-revert | gradle | 0 | 2.1s | yes |  |
| gs-graphql-server | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/graphqlserver/GraphqlServerApplication.java:3 |
| gs-graphql-server | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/graphqlserver/GraphqlServerApplication.java:3 |
| gs-graphql-server | failing-assertion | jk | 4 | 2.3s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | failing-assertion | gradle | 1 | 2.5s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/graphqlserver/BookController.java:3 |
| gs-graphql-server | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/graphqlserver/BookController.java:3 |
| gs-graphql-server | missing-resource | jk | 4 | 2.3s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | missing-resource | gradle | 1 | 2.4s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-graphql-server | green-after-revert | gradle | 0 | 2.5s | yes |  |
| gs-handling-form-submission | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/handlingformsubmission/HandlingFormSubmissionApplication.java:3 |
| gs-handling-form-submission | compile-error | gradle | 1 | 0.8s | yes | src/main/java/com/example/handlingformsubmission/HandlingFormSubmissionApplication.java:3 |
| gs-handling-form-submission | failing-assertion | jk | 4 | 2.0s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | failing-assertion | gradle | 1 | 2.3s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/handlingformsubmission/GreetingController.java:5 |
| gs-handling-form-submission | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/handlingformsubmission/GreetingController.java:5 |
| gs-handling-form-submission | missing-resource | jk | 4 | 2.1s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | missing-resource | gradle | 1 | 2.1s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-handling-form-submission | green-after-revert | gradle | 0 | 1.9s | yes |  |
| gs-reactive-rest-service | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | compile-error | mvn | 1 | 1.4s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | compile-error | gradle | 1 | 0.8s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | failing-assertion | jk | 4 | 4.8s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | failing-assertion | mvn | 1 | 5.7s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | failing-assertion | gradle | 1 | 4.9s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-reactive-rest-service | green-after-revert | mvn | 0 | 5.8s | yes |  |
| gs-reactive-rest-service | green-after-revert | gradle | 0 | 4.8s | yes |  |
| gs-securing-web | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/securingweb/SecuringWebApplication.java:3 |
| gs-securing-web | compile-error | gradle | 1 | 0.6s | yes | src/main/java/com/example/securingweb/SecuringWebApplication.java:3 |
| gs-securing-web | failing-assertion | jk | 4 | 2.4s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | failing-assertion | gradle | 1 | 2.6s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/securingweb/MvcConfig.java:4 |
| gs-securing-web | missing-dependency | gradle | 1 | 0.6s | yes | src/main/java/com/example/securingweb/MvcConfig.java:4 |
| gs-securing-web | missing-resource | jk | 4 | 2.6s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | missing-resource | gradle | 1 | 2.9s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-securing-web | green-after-revert | gradle | 0 | 2.9s | yes |  |
| gs-serving-web-content | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/servingwebcontent/ServingWebContentApplication.java:3 |
| gs-serving-web-content | compile-error | gradle | 1 | 0.9s | yes | src/main/java/com/example/servingwebcontent/ServingWebContentApplication.java:3 |
| gs-serving-web-content | failing-assertion | jk | 4 | 2.4s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | failing-assertion | gradle | 1 | 3.3s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | missing-resource | jk | 4 | 2.2s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | missing-resource | gradle | 1 | 2.6s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-serving-web-content | green-after-revert | gradle | 0 | 2.5s | yes |  |
| gs-testing-web | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | compile-error | mvn | 1 | 1.4s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | failing-assertion | jk | 4 | 4.3s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | failing-assertion | mvn | 1 | 4.4s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | failing-assertion | gradle | 1 | 3.6s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-testing-web | green-after-revert | mvn | 0 | 4.5s | yes |  |
| gs-testing-web | green-after-revert | gradle | 0 | 3.6s | yes |  |
| gs-uploading-files | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | compile-error | mvn | 1 | 1.5s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | failing-assertion | jk | 4 | 3.7s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | failing-assertion | mvn | 1 | 4.2s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | failing-assertion | gradle | 1 | 3.9s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-dependency | mvn | 1 | 1.6s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-dependency | gradle | 1 | 0.8s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-resource | jk | 4 | 3.7s | yes | com.example.uploadingfiles.FileUploadTests |
| gs-uploading-files | missing-resource | gradle | 1 | 3.6s | yes | com.example.uploadingfiles.FileUploadTests |
| gs-uploading-files | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-uploading-files | green-after-revert | mvn | 0 | 4.4s | yes |  |
| gs-uploading-files | green-after-revert | gradle | 0 | 3.8s | yes |  |
| gs-validating-form-input | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | compile-error | mvn | 1 | 1.5s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | compile-error | gradle | 1 | 0.9s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-dependency | mvn | 1 | 1.5s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-dependency | gradle | 1 | 0.7s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-resource | jk | 4 | 2.3s | yes | com.example.validatingforminput.ApplicationMockMvcTests |
| gs-validating-form-input | missing-resource | gradle | 1 | 2.4s | yes | com.example.validatingforminput.ApplicationMockMvcTests |
| gs-validating-form-input | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-validating-form-input | green-after-revert | mvn | 0 | 3.5s | yes |  |
| gs-validating-form-input | green-after-revert | gradle | 0 | 2.2s | yes |  |
| gs-rest-hateoas | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | compile-error | mvn | 1 | 1.3s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | failing-assertion | jk | 4 | 3.3s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | failing-assertion | mvn | 1 | 4.1s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | failing-assertion | gradle | 1 | 3.0s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | missing-dependency | jk | 1 | 0.7s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | missing-dependency | mvn | 1 | 1.8s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | missing-dependency | gradle | 1 | 0.8s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-rest-hateoas | green-after-revert | mvn | 0 | 4.6s | yes |  |
| gs-rest-hateoas | green-after-revert | gradle | 0 | 3.6s | yes |  |
| gs-scheduling-tasks | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/schedulingtasks/SchedulingTasksApplication.java:3 |
| gs-scheduling-tasks | compile-error | gradle | 1 | 0.7s | yes | src/main/java/com/example/schedulingtasks/SchedulingTasksApplication.java:3 |
| gs-scheduling-tasks | failing-assertion | jk | 4 | 7.1s | yes | com.example.schedulingtasks.SchedulingTasksApplicationTest |
| gs-scheduling-tasks | failing-assertion | gradle | 1 | 7.0s | yes | com.example.schedulingtasks.SchedulingTasksApplicationTest |
| gs-scheduling-tasks | missing-dependency | jk | 1 | 0.6s | yes | src/main/java/com/example/schedulingtasks/ScheduledTasks.java:22 |
| gs-scheduling-tasks | missing-dependency | gradle | 1 | 0.6s | yes | src/main/java/com/example/schedulingtasks/ScheduledTasks.java:22 |
| gs-scheduling-tasks | green-after-revert | jk | 0 | 0.2s | yes |  |
| gs-scheduling-tasks | green-after-revert | gradle | 0 | 7.3s | yes |  |
| junit-starter-gradle | compile-error | jk | 1 | 0.4s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-gradle | compile-error | gradle | 1 | 0.6s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-gradle | failing-assertion | jk | 4 | 0.8s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle | failing-assertion | gradle | 1 | 1.1s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle | missing-dependency | jk | 1 | 0.5s | yes | src/test/java/com/example/project/CalculatorTests.java:13 |
| junit-starter-gradle | missing-dependency | gradle | 1 | 0.6s | yes | src/test/java/com/example/project/CalculatorTests.java:13 |
| junit-starter-gradle | version-conflict | jk | 1 | 0.4s | yes | `com.example:junit-starter-gradle` `run-tests`: test discovery exited 70 before any test ran — test discovery failed under /home/bsant/src/scratch/agent-loop/verify/jk/junit-starter-gradle/target/classes/test: JUnitException: TestEngine with ID 'ju… |
| junit-starter-gradle | version-conflict | gradle | 1 | 0.9s | yes | `run-tests`: Execution failed for task ':test' (registered by plugin 'org.gradle.jvm-test-suite'). |
| junit-starter-gradle | green-after-revert | jk | 0 | 0.1s | yes |  |
| junit-starter-gradle | green-after-revert | gradle | 0 | 0.9s | yes |  |

# Scenario verification

Host-Id: 1a8c211a203d
Host: 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL
Date: 2026-09-29 · jk 0.14.0 · tools: jk, mvn, gradle · maven 3.9.16 · gradle 9.8.0 · last run 11m 56s

Every (repo × failure) is injected into a fresh copy of the tool's green baseline, the tool is run (`jk test`, `mvn-results test`, `gradle-results test`), and the resulting `target/jk-results.md` must (a) come from a non-zero exit, (b) name the injected failure — the edited file and line for a compile failure, the failing test class for a test failure — and (c) name nothing else. The tree is then reverted and checked clean, and after the last failure the tool runs once more and must be green.

**221 / 222 scenario runs verified**, 68 / 68 sandboxes green after revert.

| Repo | Failure | Tool | Exit | Wall | Verified | Results named |
|---|---|---|---|---|---|---|
| gs-rest-service | compile-error | jk | 1 | 2.8s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | compile-error | mvn | 1 | 2.4s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | compile-error | gradle | 1 | 6.5s | yes | src/main/java/com/example/restservice/RestServiceApplication.java:3 |
| gs-rest-service | failing-assertion | jk | 4 | 2.9s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | failing-assertion | mvn | 1 | 5.3s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | failing-assertion | gradle | 1 | 4.7s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | missing-dependency | jk | 1 | 1.7s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | missing-dependency | mvn | 1 | 2.5s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/restservice/GreetingController.java:5 |
| gs-rest-service | version-conflict | jk | 1 | 0.7s | yes | `parse-build`: ‼ Cannot resolve dependencies: |
| gs-rest-service | version-conflict | mvn | 1 | 3.5s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | version-conflict | gradle | 1 | 2.3s | yes | com.example.restservice.GreetingControllerTests |
| gs-rest-service | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-rest-service | green-after-revert | mvn | 0 | 5.2s | yes |  |
| gs-rest-service | green-after-revert | gradle | 0 | 3.6s | yes |  |
| gs-spring-boot | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | compile-error | mvn | 1 | 2.5s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | compile-error | gradle | 1 | 6.5s | yes | src/main/java/com/example/springboot/Application.java:6 |
| gs-spring-boot | failing-assertion | jk | 4 | 3.7s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | failing-assertion | mvn | 1 | 6.3s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | failing-assertion | gradle | 1 | 5.9s | yes | com.example.springboot.HelloControllerIntegrationTest |
| gs-spring-boot | missing-dependency | jk | 1 | 1.1s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | missing-dependency | mvn | 1 | 2.7s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/springboot/HelloController.java:3 |
| gs-spring-boot | version-conflict | jk | 1 | 0.4s | yes | `parse-build`: ‼ Cannot resolve dependencies: |
| gs-spring-boot | version-conflict | mvn | 1 | 3.8s | yes | com.example.springboot.HelloControllerIntegrationTest, com.example.springboot.HelloControllerTest |
| gs-spring-boot | version-conflict | gradle | 1 | 2.4s | yes | com.example.springboot.HelloControllerIntegrationTest, com.example.springboot.HelloControllerTest |
| gs-spring-boot | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-spring-boot | green-after-revert | mvn | 0 | 6.3s | yes |  |
| gs-spring-boot | green-after-revert | gradle | 0 | 5.0s | yes |  |
| gs-accessing-data-jpa | compile-error | jk | 1 | 0.9s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | compile-error | mvn | 1 | 2.4s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | compile-error | gradle | 1 | 6.5s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:6 |
| gs-accessing-data-jpa | failing-assertion | jk | 4 | 3.6s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | failing-assertion | mvn | 1 | 6.3s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | failing-assertion | gradle | 1 | 6.0s | yes | com.example.accessingdatajpa.CustomerRepositoryTests |
| gs-accessing-data-jpa | missing-dependency | jk | 1 | 1.3s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | missing-dependency | mvn | 1 | 2.4s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/accessingdatajpa/AccessingDataJpaApplication.java:3 |
| gs-accessing-data-jpa | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-accessing-data-jpa | green-after-revert | mvn | 0 | 6.4s | yes |  |
| gs-accessing-data-jpa | green-after-revert | gradle | 0 | 4.5s | yes |  |
| gs-accessing-data-r2dbc | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | compile-error | mvn | 1 | 2.4s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | compile-error | gradle | 1 | 6.7s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:7 |
| gs-accessing-data-r2dbc | failing-assertion | jk | 4 | 2.7s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | failing-assertion | mvn | 1 | 5.6s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | failing-assertion | gradle | 1 | 4.8s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-dependency | jk | 1 | 1.1s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-dependency | mvn | 1 | 2.6s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-dependency | gradle | 1 | 1.8s | yes | src/main/java/com/example/accessingdatar2dbc/AccessingDataR2dbcApplication.java:3 |
| gs-accessing-data-r2dbc | missing-resource | jk | 4 | 2.2s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-resource | mvn | 1 | 5.1s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | missing-resource | gradle | 1 | 3.5s | yes | com.example.accessingdatar2dbc.CustomerRepositoryTests |
| gs-accessing-data-r2dbc | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-accessing-data-r2dbc | green-after-revert | mvn | 0 | 5.4s | yes |  |
| gs-accessing-data-r2dbc | green-after-revert | gradle | 0 | 3.7s | yes |  |
| gs-accessing-data-rest | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | compile-error | mvn | 1 | 2.5s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | compile-error | gradle | 1 | 6.6s | yes | src/main/java/com/example/accessingdatarest/AccessingDataRestApplication.java:3 |
| gs-accessing-data-rest | failing-assertion | jk | 4 | 4.6s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | failing-assertion | mvn | 1 | 7.2s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | failing-assertion | gradle | 1 | 7.1s | yes | com.example.accessingdatarest.AccessingDataRestApplicationTests |
| gs-accessing-data-rest | missing-dependency | jk | 1 | 1.2s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | missing-dependency | mvn | 1 | 2.7s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/accessingdatarest/Person.java:3 |
| gs-accessing-data-rest | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-accessing-data-rest | green-after-revert | mvn | 0 | 7.7s | yes |  |
| gs-accessing-data-rest | green-after-revert | gradle | 0 | 5.8s | yes |  |
| gs-actuator-service | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | compile-error | mvn | 1 | 2.4s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | compile-error | gradle | 1 | 6.6s | yes | src/main/java/com/example/actuatorservice/HelloWorldApplication.java:3 |
| gs-actuator-service | failing-assertion | jk | 4 | 3.8s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | failing-assertion | mvn | 1 | 6.1s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | failing-assertion | gradle | 1 | 5.5s | yes | com.example.actuatorservice.ActuatorServiceApplicationTests |
| gs-actuator-service | missing-dependency | jk | 1 | 1.0s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | missing-dependency | mvn | 1 | 2.9s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | missing-dependency | gradle | 1 | 1.9s | yes | src/main/java/com/example/actuatorservice/HelloWorldController.java:3 |
| gs-actuator-service | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-actuator-service | green-after-revert | mvn | 0 | 6.4s | yes |  |
| gs-actuator-service | green-after-revert | gradle | 0 | 5.0s | yes |  |
| gs-batch-processing | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | compile-error | mvn | 1 | 2.4s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | compile-error | gradle | 1 | 5.8s | yes | src/main/java/com/example/batchprocessing/BatchProcessingApplication.java:3 |
| gs-batch-processing | failing-assertion | jk | 4 | 2.4s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | failing-assertion | mvn | 1 | 4.7s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | failing-assertion | gradle | 1 | 4.4s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-dependency | jk | 1 | 1.0s | yes | src/main/java/com/example/batchprocessing/BatchConfiguration.java:5 |
| gs-batch-processing | missing-dependency | mvn | 1 | 2.6s | yes | src/main/java/com/example/batchprocessing/BatchConfiguration.java:5 |
| gs-batch-processing | missing-dependency | gradle | 1 | 1.8s | yes | src/main/java/com/example/batchprocessing/JobCompletionNotificationListener.java:3 |
| gs-batch-processing | missing-resource | jk | 4 | 2.3s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-resource | mvn | 1 | 5.0s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | missing-resource | gradle | 1 | 3.5s | yes | com.example.batchprocessing.BatchConfigurationTest |
| gs-batch-processing | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-batch-processing | green-after-revert | mvn | 0 | 4.8s | yes |  |
| gs-batch-processing | green-after-revert | gradle | 0 | 3.3s | yes |  |
| gs-consuming-rest | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | compile-error | mvn | 1 | 2.3s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | compile-error | gradle | 1 | 6.4s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:7 |
| gs-consuming-rest | missing-dependency | jk | 1 | 1.0s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:3 |
| gs-consuming-rest | missing-dependency | mvn | 1 | 2.5s | yes | src/main/java/com/example/consumingrest/ConsumingRestApplication.java:3 |
| gs-consuming-rest | missing-dependency | gradle | 1 | 1.9s | yes | src/main/java/com/example/consumingrest/Quote.java:3 |
| gs-consuming-rest | green-after-revert | jk | 0 | 0.5s | yes |  |
| gs-consuming-rest | green-after-revert | mvn | 0 | 4.7s | yes |  |
| gs-consuming-rest | green-after-revert | gradle | 0 | 3.9s | yes |  |
| gs-graphql-server | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/graphqlserver/GraphqlServerApplication.java:3 |
| gs-graphql-server | compile-error | gradle | 1 | 6.4s | yes | src/main/java/com/example/graphqlserver/GraphqlServerApplication.java:3 |
| gs-graphql-server | failing-assertion | jk | 4 | 3.1s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | failing-assertion | gradle | 1 | 4.7s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | missing-dependency | jk | 1 | 1.3s | yes | src/main/java/com/example/graphqlserver/BookController.java:3 |
| gs-graphql-server | missing-dependency | gradle | 1 | 1.6s | yes | src/main/java/com/example/graphqlserver/BookController.java:3 |
| gs-graphql-server | missing-resource | jk | 4 | 2.8s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | missing-resource | gradle | 1 | 3.7s | yes | com.example.graphqlserver.BookControllerTests |
| gs-graphql-server | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-graphql-server | green-after-revert | gradle | 0 | 3.8s | yes |  |
| gs-handling-form-submission | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/handlingformsubmission/HandlingFormSubmissionApplication.java:3 |
| gs-handling-form-submission | compile-error | gradle | 1 | 5.9s | yes | src/main/java/com/example/handlingformsubmission/HandlingFormSubmissionApplication.java:3 |
| gs-handling-form-submission | failing-assertion | jk | 4 | 2.6s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | failing-assertion | gradle | 1 | 4.5s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | missing-dependency | jk | 1 | 1.9s | yes | src/main/java/com/example/handlingformsubmission/GreetingController.java:5 |
| gs-handling-form-submission | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/handlingformsubmission/GreetingController.java:5 |
| gs-handling-form-submission | missing-resource | jk | 4 | 2.6s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | missing-resource | gradle | 1 | 3.7s | yes | com.example.handlingformsubmission.HandlingFormSubmissionApplicationTest |
| gs-handling-form-submission | green-after-revert | jk | 0 | 0.3s | yes |  |
| gs-handling-form-submission | green-after-revert | gradle | 0 | 3.4s | yes |  |
| gs-reactive-rest-service | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | compile-error | mvn | 1 | 2.8s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | compile-error | gradle | 1 | 6.3s | yes | src/main/java/com/example/reactivewebservice/ReactiveWebServiceApplication.java:3 |
| gs-reactive-rest-service | failing-assertion | jk | 4 | 5.3s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | failing-assertion | mvn | 1 | 8.1s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | failing-assertion | gradle | 1 | 7.1s | yes | com.example.reactivewebservice.GreetingRouterTest |
| gs-reactive-rest-service | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-reactive-rest-service | green-after-revert | mvn | 0 | 7.8s | yes |  |
| gs-reactive-rest-service | green-after-revert | gradle | 0 | 6.1s | yes |  |
| gs-securing-web | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/securingweb/SecuringWebApplication.java:3 |
| gs-securing-web | compile-error | gradle | 1 | 6.3s | yes | src/main/java/com/example/securingweb/SecuringWebApplication.java:3 |
| gs-securing-web | failing-assertion | jk | 4 | 3.4s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | failing-assertion | gradle | 1 | 4.7s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | missing-dependency | jk | 1 | 1.3s | yes | src/main/java/com/example/securingweb/MvcConfig.java:4 |
| gs-securing-web | missing-dependency | gradle | 1 | 1.5s | yes | src/main/java/com/example/securingweb/MvcConfig.java:4 |
| gs-securing-web | missing-resource | jk | 4 | 2.9s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | missing-resource | gradle | 1 | 4.0s | yes | com.example.securingweb.SecuringWebApplicationTests |
| gs-securing-web | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-securing-web | green-after-revert | gradle | 0 | 4.0s | yes |  |
| gs-serving-web-content | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/servingwebcontent/ServingWebContentApplication.java:3 |
| gs-serving-web-content | compile-error | gradle | 1 | 8.9s | yes | src/main/java/com/example/servingwebcontent/ServingWebContentApplication.java:3 |
| gs-serving-web-content | failing-assertion | jk | 4 | 2.8s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | failing-assertion | gradle | 1 | 4.9s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | missing-resource | jk | 4 | 2.6s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | missing-resource | gradle | 1 | 3.9s | yes | com.example.servingwebcontent.ServingWebContentApplicationTest |
| gs-serving-web-content | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-serving-web-content | green-after-revert | gradle | 0 | 3.9s | yes |  |
| gs-testing-web | compile-error | jk | 1 | 1.1s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | compile-error | mvn | 1 | 2.6s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | compile-error | gradle | 1 | 6.7s | yes | src/main/java/com/example/testingweb/TestingWebApplication.java:3 |
| gs-testing-web | failing-assertion | jk | 4 | 5.4s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | failing-assertion | mvn | 1 | 7.1s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | failing-assertion | gradle | 1 | 6.6s | yes | com.example.testingweb.WebMockTest |
| gs-testing-web | green-after-revert | jk | 0 | 2.6s | yes |  |
| gs-testing-web | green-after-revert | mvn | 0 | 6.5s | yes |  |
| gs-testing-web | green-after-revert | gradle | 0 | 5.6s | yes |  |
| gs-uploading-files | compile-error | jk | 1 | 0.9s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | compile-error | mvn | 1 | 2.7s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | compile-error | gradle | 1 | 6.8s | yes | src/main/java/com/example/uploadingfiles/UploadingFilesApplication.java:4 |
| gs-uploading-files | failing-assertion | jk | 4 | 4.7s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | failing-assertion | mvn | 1 | 6.7s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | failing-assertion | gradle | 1 | 6.8s | yes | com.example.uploadingfiles.storage.FileSystemStorageServiceTests |
| gs-uploading-files | missing-dependency | jk | 1 | 1.1s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-dependency | mvn | 1 | 2.8s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-dependency | gradle | 1 | 1.9s | yes | src/main/java/com/example/uploadingfiles/FileUploadController.java:18 |
| gs-uploading-files | missing-resource | jk | 4 | 3.7s | yes | com.example.uploadingfiles.FileUploadTests |
| gs-uploading-files | missing-resource | gradle | 1 | 5.6s | yes | com.example.uploadingfiles.FileUploadTests |
| gs-uploading-files | green-after-revert | jk | 0 | 0.5s | yes |  |
| gs-uploading-files | green-after-revert | mvn | 0 | 7.3s | yes |  |
| gs-uploading-files | green-after-revert | gradle | 0 | 5.6s | yes |  |
| gs-validating-form-input | compile-error | jk | 1 | 0.8s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | compile-error | mvn | 1 | 3.0s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | compile-error | gradle | 1 | 6.1s | yes | src/main/java/com/example/validatingforminput/ValidatingFormInputApplication.java:3 |
| gs-validating-form-input | missing-dependency | jk | 1 | 1.9s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-dependency | mvn | 1 | 2.6s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-dependency | gradle | 1 | 1.9s | yes | src/main/java/com/example/validatingforminput/PersonForm.java:3 |
| gs-validating-form-input | missing-resource | jk | 4 | 2.9s | yes | com.example.validatingforminput.ApplicationMockMvcTests |
| gs-validating-form-input | missing-resource | gradle | 1 | 4.5s | yes | com.example.validatingforminput.ApplicationMockMvcTests |
| gs-validating-form-input | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-validating-form-input | green-after-revert | mvn | 0 | 5.5s | yes |  |
| gs-validating-form-input | green-after-revert | gradle | 0 | 3.8s | yes |  |
| gs-rest-hateoas | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | compile-error | mvn | 1 | 2.5s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | compile-error | gradle | 1 | 6.0s | yes | src/main/java/com/example/resthateoas/RestHateoasApplication.java:3 |
| gs-rest-hateoas | failing-assertion | jk | 4 | 3.6s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | failing-assertion | mvn | 1 | 5.9s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | failing-assertion | gradle | 1 | 5.6s | yes | com.example.resthateoas.GreetingMockMvcTests |
| gs-rest-hateoas | missing-dependency | jk | 1 | 0.9s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | missing-dependency | mvn | 1 | 2.4s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | missing-dependency | gradle | 1 | 1.7s | yes | src/main/java/com/example/resthateoas/Greeting.java:3 |
| gs-rest-hateoas | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-rest-hateoas | green-after-revert | mvn | 0 | 6.2s | yes |  |
| gs-rest-hateoas | green-after-revert | gradle | 0 | 4.5s | yes |  |
| gs-scheduling-tasks | compile-error | jk | 1 | 0.7s | yes | src/main/java/com/example/schedulingtasks/SchedulingTasksApplication.java:3 |
| gs-scheduling-tasks | compile-error | gradle | 1 | 6.0s | yes | src/main/java/com/example/schedulingtasks/SchedulingTasksApplication.java:3 |
| gs-scheduling-tasks | failing-assertion | jk | 4 | 7.3s | yes | com.example.schedulingtasks.SchedulingTasksApplicationTest |
| gs-scheduling-tasks | failing-assertion | gradle | 1 | 9.0s | yes | com.example.schedulingtasks.SchedulingTasksApplicationTest |
| gs-scheduling-tasks | missing-dependency | jk | 1 | 0.8s | yes | src/main/java/com/example/schedulingtasks/ScheduledTasks.java:22 |
| gs-scheduling-tasks | missing-dependency | gradle | 1 | 1.5s | yes | src/main/java/com/example/schedulingtasks/ScheduledTasks.java:22 |
| gs-scheduling-tasks | green-after-revert | jk | 0 | 0.4s | yes |  |
| gs-scheduling-tasks | green-after-revert | gradle | 0 | 8.2s | yes |  |
| junit-starter-gradle | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-gradle | compile-error | gradle | 1 | 4.9s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-gradle | failing-assertion | jk | 4 | 1.3s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle | failing-assertion | gradle | 1 | 2.4s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle | missing-dependency | jk | 1 | 0.9s | yes | src/test/java/com/example/project/CalculatorTests.java:13 |
| junit-starter-gradle | missing-dependency | gradle | 1 | 1.4s | yes | src/test/java/com/example/project/CalculatorTests.java:13 |
| junit-starter-gradle | version-conflict | jk | 1 | 0.7s | yes | `com.example:junit-starter-gradle` `run-tests`: test discovery exited 70 before any test ran — TestEngine with ID 'junit-jupiter' failed to discover tests |
| junit-starter-gradle | version-conflict | gradle | 1 | 1.8s | yes | `run-tests`: Execution failed for task ':test' (registered by plugin 'org.gradle.jvm-test-suite'). |
| junit-starter-gradle | green-after-revert | jk | 0 | 0.3s | yes |  |
| junit-starter-gradle | green-after-revert | gradle | 0 | 1.9s | yes |  |
| gs-multi-module | compile-error | jk | 1 | 1.1s | yes | library/src/main/java/com/example/multimodule/service/MyService.java:17 |
| gs-multi-module | compile-error | mvn | 1 | 2.3s | yes | library/src/main/java/com/example/multimodule/service/MyService.java:17 |
| gs-multi-module | compile-error | gradle | 1 | 6.3s | yes | library/src/main/java/com/example/multimodule/service/MyService.java:17 |
| gs-multi-module | failing-assertion | jk | 4 | 3.1s | yes | com.example.multimodule.service.MyServiceTest |
| gs-multi-module | failing-assertion | mvn | 1 | 4.2s | yes | com.example.multimodule.service.MyServiceTest |
| gs-multi-module | failing-assertion | gradle | 1 | 6.6s | yes | com.example.multimodule.service.MyServiceTest |
| gs-multi-module | missing-resource | jk | 4 | 2.8s | yes | com.example.multimodule.application.DemoApplicationTest |
| gs-multi-module | missing-resource | gradle | 1 | 4.2s | yes | com.example.multimodule.application.DemoApplicationTest |
| gs-multi-module | version-conflict | jk | 4 | 2.6s | yes | com.example.multimodule.service.MyServiceTest, com.example.multimodule.application.DemoApplicationTest |
| gs-multi-module | version-conflict | mvn | 1 | 5.1s | yes | com.example.multimodule.application.DemoApplicationTest |
| gs-multi-module | version-conflict | gradle | 1 | 2.2s | yes | com.example.multimodule.application.DemoApplicationTest |
| gs-multi-module | green-after-revert | jk | 0 | 0.5s | yes |  |
| gs-multi-module | green-after-revert | mvn | 0 | 6.9s | yes |  |
| gs-multi-module | green-after-revert | gradle | 0 | 5.5s | yes |  |
| junit-starter-maven | compile-error | jk | 1 | 0.6s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-maven | compile-error | mvn | 1 | 2.2s | yes | src/main/java/com/example/project/Calculator.java:16 |
| junit-starter-maven | failing-assertion | jk | 4 | 1.1s | yes | com.example.project.CalculatorTests |
| junit-starter-maven | failing-assertion | mvn | 1 | 3.1s | yes | com.example.project.CalculatorTests |
| junit-starter-maven | green-after-revert | jk | 0 | 0.3s | yes |  |
| junit-starter-maven | green-after-revert | mvn | 0 | 3.0s | yes |  |
| commons-cli | compile-error | jk | 1 | 0.8s | yes | src/main/java/org/apache/commons/cli/Util.java:80 |
| commons-cli | compile-error | mvn | 1 | 4.7s | yes | src/main/java/org/apache/commons/cli/Util.java:80 |
| commons-cli | failing-assertion | jk | 4 | 2.2s | yes | org.apache.commons.cli.UtilTest |
| commons-cli | failing-assertion | mvn | 1 | 10.1s | yes | org.apache.commons.cli.UtilTest |
| commons-cli | missing-dependency | jk | 1 | 1.9s | yes | src/test/java/org/apache/commons/cli/SolrCliTest.java:24 |
| commons-cli | missing-dependency | mvn | 1 | 5.9s | yes | src/test/java/org/apache/commons/cli/SolrCliTest.java:24 |
| commons-cli | green-after-revert | jk | 0 | 0.4s | yes |  |
| commons-cli | green-after-revert | mvn | 0 | 8.5s | yes |  |
| spring-petclinic | compile-error | jk | 1 | 1.0s | yes | src/main/java/org/springframework/samples/petclinic/owner/Owner.java:77 |
| spring-petclinic | compile-error | mvn | 1 | 6.0s | yes | src/main/java/org/springframework/samples/petclinic/owner/Owner.java:77 |
| spring-petclinic | failing-assertion | jk | 4 | 28.8s | yes | org.springframework.samples.petclinic.owner.PetTypeFormatterTests |
| spring-petclinic | failing-assertion | mvn | 1 | 102.8s | **no** — unexpected failing tests: ['org.springframework.samples.petclinic.PostgresIntegrationTests'] |  |
| spring-petclinic | missing-dependency | jk | 1 | 3.6s | yes | src/main/java/org/springframework/samples/petclinic/system/CacheConfiguration.java:24 |
| spring-petclinic | missing-dependency | mvn | 1 | 6.1s | yes | src/main/java/org/springframework/samples/petclinic/system/CacheConfiguration.java:24 |
| spring-petclinic | missing-resource | jk | 4 | 27.9s | yes | org.springframework.samples.petclinic.PetClinicIntegrationTests, org.springframework.samples.petclinic.PetClinicConcurrencyTests, org.springframework.samples.petclinic.service.ClinicServiceTests |
| spring-petclinic | missing-resource | mvn | 1 | 43.2s | yes | org.springframework.samples.petclinic.PetClinicConcurrencyTests, org.springframework.samples.petclinic.PetClinicIntegrationTests, org.springframework.samples.petclinic.service.ClinicServiceTests |
| spring-petclinic | green-after-revert | jk | 0 | 0.5s | yes |  |
| spring-petclinic | green-after-revert | mvn | 0 | 46.3s | yes |  |
| junit-starter-maven-kotlin | compile-error | jk | 1 | 2.0s | yes | src/main/kotlin/com/example/project/Calculator.kt:16 |
| junit-starter-maven-kotlin | compile-error | mvn | 1 | 3.3s | yes | src/main/kotlin/com/example/project/Calculator.kt:16 |
| junit-starter-maven-kotlin | failing-assertion | jk | 4 | 1.1s | yes | com.example.project.CalculatorTests |
| junit-starter-maven-kotlin | failing-assertion | mvn | 1 | 4.4s | yes | com.example.project.CalculatorTests |
| junit-starter-maven-kotlin | missing-dependency | jk | 1 | 1.5s | yes | src/test/kotlin/com/example/project/CalculatorTests.kt:17 |
| junit-starter-maven-kotlin | missing-dependency | mvn | 1 | 3.6s | yes | src/test/kotlin/com/example/project/CalculatorTests.kt:17 |
| junit-starter-maven-kotlin | green-after-revert | jk | 0 | 0.3s | yes |  |
| junit-starter-maven-kotlin | green-after-revert | mvn | 0 | 4.2s | yes |  |
| junit-starter-gradle-kotlin | compile-error | jk | 1 | 1.0s | yes | src/main/kotlin/com/example/project/Calculator.kt:16 |
| junit-starter-gradle-kotlin | compile-error | gradle | 1 | 7.7s | yes | src/main/kotlin/com/example/project/Calculator.kt:16 |
| junit-starter-gradle-kotlin | failing-assertion | jk | 4 | 1.1s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle-kotlin | failing-assertion | gradle | 1 | 3.6s | yes | com.example.project.CalculatorTests |
| junit-starter-gradle-kotlin | missing-dependency | jk | 1 | 1.1s | yes | src/test/kotlin/com/example/project/CalculatorTests.kt:13 |
| junit-starter-gradle-kotlin | missing-dependency | gradle | 1 | 1.7s | yes | src/test/kotlin/com/example/project/CalculatorTests.kt:13 |
| junit-starter-gradle-kotlin | green-after-revert | jk | 0 | 0.3s | yes |  |
| junit-starter-gradle-kotlin | green-after-revert | gradle | 0 | 2.4s | yes |  |
| spring-petclinic-kotlin | compile-error | jk | 1 | 6.9s | yes | src/main/kotlin/org/springframework/samples/petclinic/vet/Vet.kt:45 |
| spring-petclinic-kotlin | compile-error | gradle | 1 | 11.2s | yes | src/main/kotlin/org/springframework/samples/petclinic/vet/Vet.kt:45 |
| spring-petclinic-kotlin | failing-assertion | jk | 4 | 6.8s | yes | org.springframework.samples.petclinic.vet.VetTest |
| spring-petclinic-kotlin | failing-assertion | gradle | 1 | 14.7s | yes | org.springframework.samples.petclinic.vet.VetTest |
| spring-petclinic-kotlin | missing-dependency | jk | 1 | 30.5s | yes | src/main/kotlin/org/springframework/samples/petclinic/system/CacheConfig.kt:22 |
| spring-petclinic-kotlin | missing-dependency | gradle | 1 | 2.3s | yes | src/main/kotlin/org/springframework/samples/petclinic/system/CacheConfig.kt:22 |
| spring-petclinic-kotlin | missing-resource | jk | 4 | 5.2s | yes | org.springframework.samples.petclinic.vet.VetRepositoryTest, org.springframework.samples.petclinic.owner.PetRepositoryTest, org.springframework.samples.petclinic.visit.VisitRepositoryTest, org.springframework.samples.petclinic.owner.OwnerRepositoryTest, org.springframework.samples.petclinic.PetclinicIntegrationTests |
| spring-petclinic-kotlin | missing-resource | gradle | 1 | 11.3s | yes | org.springframework.samples.petclinic.PetclinicIntegrationTests, org.springframework.samples.petclinic.owner.OwnerRepositoryTest, org.springframework.samples.petclinic.owner.PetRepositoryTest, org.springframework.samples.petclinic.vet.VetRepositoryTest, org.springframework.samples.petclinic.visit.VisitRepositoryTest |
| spring-petclinic-kotlin | green-after-revert | jk | 0 | 0.4s | yes |  |
| spring-petclinic-kotlin | green-after-revert | gradle | 0 | 11.8s | yes |  |
| tut-spring-boot-kotlin | compile-error | jk | 1 | 4.6s | yes | src/main/kotlin/com/example/blog/Extensions.kt:21 |
| tut-spring-boot-kotlin | compile-error | gradle | 1 | 9.5s | yes | src/main/kotlin/com/example/blog/Extensions.kt:21 |
| tut-spring-boot-kotlin | failing-assertion | jk | 4 | 4.7s | yes | com.example.blog.IntegrationTests |
| tut-spring-boot-kotlin | failing-assertion | gradle | 1 | 9.8s | yes | com.example.blog.IntegrationTests |
| tut-spring-boot-kotlin | missing-dependency | jk | 1 | 6.2s | yes | src/test/kotlin/com/example/blog/HttpControllersTests.kt:3 |
| tut-spring-boot-kotlin | missing-dependency | gradle | 1 | 2.7s | yes | src/test/kotlin/com/example/blog/HttpControllersTests.kt:3 |
| tut-spring-boot-kotlin | missing-resource | jk | 4 | 3.7s | yes | com.example.blog.RepositoriesTests |
| tut-spring-boot-kotlin | missing-resource | gradle | 1 | 7.8s | yes | com.example.blog.BlogApplicationTests, com.example.blog.IntegrationTests, com.example.blog.RepositoriesTests |
| tut-spring-boot-kotlin | green-after-revert | jk | 0 | 0.4s | yes |  |
| tut-spring-boot-kotlin | green-after-revert | gradle | 0 | 7.7s | yes |  |

# $name$

Helidon SE with a database (Helidon 27, JDK 27): a Pokemon REST service on Helidon DB Client,
scaffolded from jk's `helidon/database` template, the counterpart of `helidon init`'s database
application.
$if(db_h2.truthy)$
The database is an in-memory H2 inside the application: there is nothing to install.
$endif$
$if(db_mysql.truthy)$
The application talks to MySQL at `127.0.0.1:3306`, database `pokemon` (`db.connection` in
`application.yaml`):

```bash
docker run --rm --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root \
    -e MYSQL_DATABASE=pokemon -e MYSQL_USER=user -e MYSQL_PASSWORD=changeit mysql
```
$endif$
$if(db_oracledb.truthy)$
The application talks to Oracle Database at `localhost:1521/XE` (`db.connection` in
`application.yaml`):

```bash
docker run --rm --name oracle -p 1521:1521 -e ORACLE_PASSWORD=oracle gvenzl/oracle-free
```
$endif$
$if(db_mongodb.truthy)$
The application talks to MongoDB at `127.0.0.1:27017`, database `pokemon` (`db.connection` in
`application.yaml`):

```bash
docker run --rm --name mongo -p 27017:27017 mongo
```
$endif$
$if(db_h2.truthy)$
$else$
The tests run on an in-memory H2 instead (`application-test.yaml`, the test config profile).
$endif$

```bash
jk build     # target/$name$.jar + target/libs/, Helidon's deployable
jk test      # MainTest, on a free port, under the test config profile
jk run       # serve on :8080
jk dev       # serve, and restart on every change
curl -s localhost:8080/db/pokemon
curl -s localhost:8080/db/pokemon/name/Squirtle
curl -s -X POST -H 'Content-Type: application/json' -d '{"id":7,"name":"Rattata","idType":1}' localhost:8080/db/pokemon
curl -s -X DELETE localhost:8080/db/pokemon/7
$if(health.truthy)$
curl -s -i localhost:8080/observe/health
$endif$
$if(metrics.truthy)$
curl -s localhost:8080/observe/metrics
$endif$
```

`[helidon] version` imports Helidon's BOM, so dependencies carry no version, and contributes
Helidon's annotation processors. `jk update` moves it to a newer Helidon. Every SQL statement is
named in `application.yaml` under `db.statements`; `PokemonService` runs them by name.
$if(docker.truthy)$

`jk image` builds the container image: the jar and `libs/` as layers, started with `java -jar`,
what Helidon's `Dockerfile` does.
$endif$
$if(jlink.truthy)$

`jk build` also writes `target/$name$-jri/`, a runtime image with an AOT cache: run it with
`target/$name$-jri/bin/start`.
$endif$
$if(k8s.truthy)$

`app.yaml` deploys the image to Kubernetes: `kubectl create -f app.yaml`.
$endif$

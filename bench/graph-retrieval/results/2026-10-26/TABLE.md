# Graph retrieval — 2026-10-26

Tokens are characters / 4. Recall is the share of the compiler's answer the output names at its `file:line` (grep-read: or reads the file of). Precision is the share of named code locations that are part of the answer.

| Repo | LOC | Arm | Questions | Median tokens | Mean recall | Mean precision | Tokens per found |
|---|---:|---|---:|---:|---:|---:|---:|
| spring-petclinic-kotlin | 1889 | graphify-query | 29 | 485 | 0.43 | 0.16 | 1211 |
| spring-petclinic-kotlin | 1889 | graphify-tool | 29 | 20 | 0.41 | 0.81 | 64 |
| spring-petclinic-kotlin | 1889 | grep-read | 29 | 855 | 0.70 | 0.43 | 1694 |
| spring-petclinic-kotlin | 1889 | jk-query | 29 | 1554 | 0.96 | 0.25 | 548 |
| spring-petclinic-kotlin | 1889 | jk-tool | 29 | 73 | 0.97 | 0.92 | 37 |
| spring-petclinic-kotlin | 1889 | rql-explore | 29 | 357 | 0.31 | 0.00 | 1482 |
| spring-petclinic-kotlin | 1889 | rql-tool | 29 | 63 | 0.34 | 0.00 | 353 |
| spring-petclinic | 3635 | graphify-query | 28 | 1631 | 0.97 | 0.14 | 839 |
| spring-petclinic | 3635 | graphify-tool | 28 | 202 | 0.94 | 0.63 | 92 |
| spring-petclinic | 3635 | grep-read | 28 | 2212 | 0.98 | 0.38 | 2000 |
| spring-petclinic | 3635 | jk-query | 28 | 1556 | 0.98 | 0.15 | 552 |
| spring-petclinic | 3635 | jk-tool | 28 | 76 | 0.97 | 0.91 | 42 |
| spring-petclinic | 3635 | rql-explore | 28 | 1348 | 0.41 | 0.00 | 2457 |
| spring-petclinic | 3635 | rql-tool | 28 | 136 | 0.12 | 0.92 | 524 |
| commons-cli | 17862 | graphify-query | 37 | 1628 | 0.59 | 0.07 | 1120 |
| commons-cli | 17862 | graphify-tool | 37 | 145 | 0.68 | 0.56 | 164 |
| commons-cli | 17862 | grep-read | 37 | 24509 | 0.96 | 0.29 | 11328 |
| commons-cli | 17862 | jk-query | 37 | 1559 | 0.68 | 0.07 | 790 |
| commons-cli | 17862 | jk-tool | 37 | 139 | 0.84 | 0.71 | 52 |
| commons-cli | 17862 | rql-explore | 37 | 1851 | 0.45 | 0.00 | 2000 |
| commons-cli | 17862 | rql-tool | 37 | 193 | 0.17 | 0.63 | 1644 |
| commons-io | 105209 | graphify-query | 50 | 1622 | 0.54 | 0.08 | 1411 |
| commons-io | 105209 | graphify-tool | 50 | 142 | 0.60 | 0.46 | 139 |
| commons-io | 105209 | grep-read | 50 | 26037 | 0.97 | 0.23 | 21019 |
| commons-io | 105209 | jk-query | 50 | 1558 | 0.79 | 0.10 | 786 |
| commons-io | 105209 | jk-tool | 50 | 90 | 0.85 | 0.78 | 52 |
| commons-collections | 129244 | graphify-query | 50 | 1621 | 0.45 | 0.08 | 1096 |
| commons-collections | 129244 | graphify-tool | 50 | 184 | 0.71 | 0.64 | 80 |
| commons-collections | 129244 | grep-read | 50 | 29064 | 0.95 | 0.28 | 12785 |
| commons-collections | 129244 | jk-query | 50 | 1558 | 0.70 | 0.10 | 669 |
| commons-collections | 129244 | jk-tool | 50 | 128 | 0.96 | 0.87 | 41 |
| commons-lang | 188332 | graphify-query | 50 | 1618 | 0.57 | 0.12 | 793 |
| commons-lang | 188332 | graphify-tool | 50 | 150 | 0.61 | 0.49 | 111 |
| commons-lang | 188332 | grep-read | 50 | 22696 | 0.96 | 0.33 | 20371 |
| commons-lang | 188332 | jk-query | 50 | 1558 | 0.71 | 0.12 | 609 |
| commons-lang | 188332 | jk-tool | 50 | 98 | 0.86 | 0.71 | 72 |
| jk | 679533 | graphify-query | 50 | 1629 | 0.56 | 0.07 | 1109 |
| jk | 679533 | graphify-tool | 50 | 88 | 0.48 | 0.46 | 128 |
| jk | 679533 | grep-read | 50 | 32527 | 0.97 | 0.32 | 17482 |
| jk | 679533 | jk-query | 50 | 1562 | 0.84 | 0.08 | 617 |
| jk | 679533 | jk-tool | 50 | 80 | 0.99 | 0.95 | 35 |

## By question kind

| Repo | Kind | Arm | Median tokens | Mean recall |
|---|---|---|---:|---:|
| commons-cli | callers | graphify-query | 1634 | 0.45 |
| commons-cli | callers | graphify-tool | 143 | 0.55 |
| commons-cli | callers | grep-read | 33877 | 1.00 |
| commons-cli | callers | jk-query | 1556 | 0.53 |
| commons-cli | callers | jk-tool | 180 | 0.71 |
| commons-cli | callers | rql-explore | 1848 | 0.28 |
| commons-cli | callers | rql-tool | 771 | 0.00 |
| commons-cli | definition | graphify-query | 1625 | 0.90 |
| commons-cli | definition | graphify-tool | 270 | 1.00 |
| commons-cli | definition | grep-read | 11056 | 1.00 |
| commons-cli | definition | jk-query | 1566 | 0.90 |
| commons-cli | definition | jk-tool | 43 | 1.00 |
| commons-cli | definition | rql-explore | 1880 | 0.70 |
| commons-cli | definition | rql-tool | 17 | 0.10 |
| commons-cli | implementers | graphify-query | 1613 | 0.85 |
| commons-cli | implementers | graphify-tool | 123 | 1.00 |
| commons-cli | implementers | grep-read | 5730 | 0.68 |
| commons-cli | implementers | jk-query | 1546 | 1.00 |
| commons-cli | implementers | jk-tool | 140 | 1.00 |
| commons-cli | implementers | rql-explore | 1822 | 0.25 |
| commons-cli | implementers | rql-tool | 111 | 0.55 |
| commons-cli | overrides | graphify-query | 1625 | 0.11 |
| commons-cli | overrides | graphify-tool | 836 | 0.00 |
| commons-cli | overrides | grep-read | 80227 | 1.00 |
| commons-cli | overrides | jk-query | 1559 | 0.50 |
| commons-cli | overrides | jk-tool | 214 | 1.00 |
| commons-cli | overrides | rql-explore | 1788 | 1.00 |
| commons-cli | overrides | rql-tool | 575 | 1.00 |
| commons-collections | callers | graphify-query | 1536 | 0.35 |
| commons-collections | callers | graphify-tool | 119 | 0.75 |
| commons-collections | callers | grep-read | 37088 | 0.99 |
| commons-collections | callers | jk-query | 1557 | 0.81 |
| commons-collections | callers | jk-tool | 136 | 0.91 |
| commons-collections | definition | graphify-query | 1620 | 0.70 |
| commons-collections | definition | graphify-tool | 209 | 0.90 |
| commons-collections | definition | grep-read | 2596 | 1.00 |
| commons-collections | definition | jk-query | 1559 | 0.80 |
| commons-collections | definition | jk-tool | 48 | 1.00 |
| commons-collections | implementers | graphify-query | 1619 | 0.80 |
| commons-collections | implementers | graphify-tool | 155 | 0.90 |
| commons-collections | implementers | grep-read | 14114 | 0.77 |
| commons-collections | implementers | jk-query | 1548 | 0.74 |
| commons-collections | implementers | jk-tool | 163 | 1.00 |
| commons-collections | overrides | graphify-query | 1630 | 0.05 |
| commons-collections | overrides | graphify-tool | 267 | 0.23 |
| commons-collections | overrides | grep-read | 135626 | 1.00 |
| commons-collections | overrides | jk-query | 1566 | 0.35 |
| commons-collections | overrides | jk-tool | 200 | 1.00 |
| commons-io | callers | graphify-query | 1529 | 0.40 |
| commons-io | callers | graphify-tool | 69 | 0.48 |
| commons-io | callers | grep-read | 81058 | 0.97 |
| commons-io | callers | jk-query | 1560 | 0.58 |
| commons-io | callers | jk-tool | 109 | 0.63 |
| commons-io | definition | graphify-query | 1616 | 0.80 |
| commons-io | definition | graphify-tool | 203 | 1.00 |
| commons-io | definition | grep-read | 4359 | 1.00 |
| commons-io | definition | jk-query | 1560 | 0.90 |
| commons-io | definition | jk-tool | 50 | 1.00 |
| commons-io | implementers | graphify-query | 1626 | 0.92 |
| commons-io | implementers | graphify-tool | 122 | 0.90 |
| commons-io | implementers | grep-read | 10228 | 0.93 |
| commons-io | implementers | jk-query | 1552 | 0.92 |
| commons-io | implementers | jk-tool | 109 | 1.00 |
| commons-io | overrides | graphify-query | 1632 | 0.20 |
| commons-io | overrides | graphify-tool | 182 | 0.15 |
| commons-io | overrides | grep-read | 81936 | 1.00 |
| commons-io | overrides | jk-query | 1561 | 0.97 |
| commons-io | overrides | jk-tool | 86 | 1.00 |
| commons-lang | callers | graphify-query | 1420 | 0.47 |
| commons-lang | callers | graphify-tool | 94 | 0.60 |
| commons-lang | callers | grep-read | 36962 | 1.00 |
| commons-lang | callers | jk-query | 1554 | 0.65 |
| commons-lang | callers | jk-tool | 98 | 0.65 |
| commons-lang | definition | graphify-query | 1614 | 0.70 |
| commons-lang | definition | graphify-tool | 270 | 0.70 |
| commons-lang | definition | grep-read | 13531 | 1.00 |
| commons-lang | definition | jk-query | 1566 | 0.80 |
| commons-lang | definition | jk-tool | 56 | 1.00 |
| commons-lang | implementers | graphify-query | 1626 | 0.86 |
| commons-lang | implementers | graphify-tool | 144 | 0.98 |
| commons-lang | implementers | grep-read | 7494 | 0.78 |
| commons-lang | implementers | jk-query | 1551 | 0.87 |
| commons-lang | implementers | jk-tool | 166 | 1.00 |
| commons-lang | overrides | graphify-query | 1632 | 0.33 |
| commons-lang | overrides | graphify-tool | 170 | 0.15 |
| commons-lang | overrides | grep-read | 63120 | 1.00 |
| commons-lang | overrides | jk-query | 1569 | 0.58 |
| commons-lang | overrides | jk-tool | 104 | 1.00 |
| jk | callers | graphify-query | 1636 | 0.39 |
| jk | callers | graphify-tool | 52 | 0.46 |
| jk | callers | grep-read | 42962 | 1.00 |
| jk | callers | jk-query | 1567 | 0.74 |
| jk | callers | jk-tool | 99 | 0.97 |
| jk | definition | graphify-query | 1632 | 0.90 |
| jk | definition | graphify-tool | 136 | 0.90 |
| jk | definition | grep-read | 6304 | 0.90 |
| jk | definition | jk-query | 1565 | 1.00 |
| jk | definition | jk-tool | 33 | 1.00 |
| jk | implementers | graphify-query | 1624 | 0.82 |
| jk | implementers | graphify-tool | 62 | 0.56 |
| jk | implementers | grep-read | 7954 | 0.98 |
| jk | implementers | jk-query | 1543 | 0.90 |
| jk | implementers | jk-tool | 99 | 1.00 |
| jk | overrides | graphify-query | 1628 | 0.30 |
| jk | overrides | graphify-tool | 122 | 0.02 |
| jk | overrides | grep-read | 80025 | 0.97 |
| jk | overrides | jk-query | 1559 | 0.85 |
| jk | overrides | jk-tool | 109 | 1.00 |
| spring-petclinic | callers | graphify-query | 1638 | 0.94 |
| spring-petclinic | callers | graphify-tool | 185 | 0.89 |
| spring-petclinic | callers | grep-read | 7666 | 1.00 |
| spring-petclinic | callers | jk-query | 1564 | 0.96 |
| spring-petclinic | callers | jk-tool | 138 | 0.94 |
| spring-petclinic | callers | rql-explore | 1325 | 0.10 |
| spring-petclinic | callers | rql-tool | 276 | 0.00 |
| spring-petclinic | definition | graphify-query | 1632 | 1.00 |
| spring-petclinic | definition | graphify-tool | 217 | 1.00 |
| spring-petclinic | definition | grep-read | 1564 | 1.00 |
| spring-petclinic | definition | jk-query | 1552 | 1.00 |
| spring-petclinic | definition | jk-tool | 32 | 1.00 |
| spring-petclinic | definition | rql-explore | 1650 | 1.00 |
| spring-petclinic | definition | rql-tool | 13 | 0.10 |
| spring-petclinic | implementers | graphify-query | 1609 | 1.00 |
| spring-petclinic | implementers | graphify-tool | 89 | 1.00 |
| spring-petclinic | implementers | grep-read | 2156 | 0.79 |
| spring-petclinic | implementers | jk-query | 1541 | 1.00 |
| spring-petclinic | implementers | jk-tool | 109 | 1.00 |
| spring-petclinic | implementers | rql-explore | 1002 | 0.00 |
| spring-petclinic | implementers | rql-tool | 119 | 0.79 |
| spring-petclinic-kotlin | callers | graphify-query | 103 | 0.09 |
| spring-petclinic-kotlin | callers | graphify-tool | 20 | 0.00 |
| spring-petclinic-kotlin | callers | grep-read | 2169 | 0.50 |
| spring-petclinic-kotlin | callers | jk-query | 1554 | 0.99 |
| spring-petclinic-kotlin | callers | jk-tool | 104 | 0.95 |
| spring-petclinic-kotlin | callers | rql-explore | 356 | 0.00 |
| spring-petclinic-kotlin | callers | rql-tool | 110 | 0.00 |
| spring-petclinic-kotlin | definition | graphify-query | 1609 | 0.80 |
| spring-petclinic-kotlin | definition | graphify-tool | 62 | 0.90 |
| spring-petclinic-kotlin | definition | grep-read | 619 | 1.00 |
| spring-petclinic-kotlin | definition | jk-query | 1552 | 0.90 |
| spring-petclinic-kotlin | definition | jk-tool | 32 | 1.00 |
| spring-petclinic-kotlin | definition | rql-explore | 356 | 0.90 |
| spring-petclinic-kotlin | definition | rql-tool | 61 | 1.00 |
| spring-petclinic-kotlin | implementers | graphify-query | 4662 | 1.00 |
| spring-petclinic-kotlin | implementers | graphify-tool | 89 | 1.00 |
| spring-petclinic-kotlin | implementers | grep-read | 1269 | 0.79 |
| spring-petclinic-kotlin | implementers | jk-query | 1542 | 1.00 |
| spring-petclinic-kotlin | implementers | jk-tool | 111 | 1.00 |
| spring-petclinic-kotlin | implementers | rql-explore | 738 | 0.00 |
| spring-petclinic-kotlin | implementers | rql-tool | 69 | 0.00 |

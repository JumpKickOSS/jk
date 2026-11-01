# Graph retrieval — 2026-11-01

Tokens are characters / 4. Recall is the share of the compiler's answer the output names at its `file:line` (grep-read: or reads the file of). Precision is the share of named code locations that are part of the answer.

| Repo | LOC | Arm | Questions | Median tokens | Mean recall | Mean precision | Tokens per found |
|---|---:|---|---:|---:|---:|---:|---:|
| gs-multi-module | 83 | graphify-query | 4 | 2233 | 1.00 | 0.09 | 2377 |
| gs-multi-module | 83 | graphify-tool | 4 | 158 | 1.00 | 0.72 | 160 |
| gs-multi-module | 83 | grep-read | 4 | 171 | 1.00 | 0.75 | 192 |
| gs-multi-module | 83 | jk-query | 4 | 1568 | 1.00 | 0.14 | 1473 |
| gs-multi-module | 83 | jk-tool | 4 | 32 | 1.00 | 1.00 | 32 |
| gs-multi-module | 83 | rql-explore | 4 | 262 | 1.00 | 0.00 | 290 |
| gs-multi-module | 83 | rql-tool | 4 | 5 | 0.00 | 0.00 | inf |
| gs-uploading-files | 416 | graphify-query | 10 | 1626 | 1.00 | 0.07 | 2235 |
| gs-uploading-files | 416 | graphify-tool | 10 | 218 | 1.00 | 0.52 | 218 |
| gs-uploading-files | 416 | grep-read | 10 | 551 | 1.00 | 0.20 | 583 |
| gs-uploading-files | 416 | jk-query | 10 | 1568 | 1.00 | 0.06 | 1567 |
| gs-uploading-files | 416 | jk-tool | 10 | 32 | 1.00 | 1.00 | 33 |
| gs-uploading-files | 416 | rql-explore | 10 | 936 | 1.00 | 0.00 | 959 |
| gs-uploading-files | 416 | rql-tool | 10 | 12 | 0.30 | 0.50 | 111 |
| spring-petclinic-kotlin | 1889 | graphify-query | 29 | 485 | 0.43 | 0.16 | 1211 |
| spring-petclinic-kotlin | 1889 | graphify-tool | 29 | 20 | 0.41 | 0.81 | 64 |
| spring-petclinic-kotlin | 1889 | grep-read | 29 | 855 | 0.70 | 0.43 | 1694 |
| spring-petclinic-kotlin | 1889 | jk-query | 29 | 1551 | 1.00 | 0.25 | 535 |
| spring-petclinic-kotlin | 1889 | jk-tool | 29 | 155 | 0.99 | 0.97 | 57 |
| spring-petclinic-kotlin | 1889 | rql-explore | 29 | 357 | 0.31 | 0.00 | 1482 |
| spring-petclinic-kotlin | 1889 | rql-tool | 29 | 63 | 0.34 | 0.00 | 353 |
| spring-petclinic | 3635 | graphify-query | 28 | 1631 | 0.97 | 0.14 | 839 |
| spring-petclinic | 3635 | graphify-tool | 28 | 202 | 0.94 | 0.63 | 92 |
| spring-petclinic | 3635 | grep-read | 28 | 2212 | 0.98 | 0.38 | 2000 |
| spring-petclinic | 3635 | jk-query | 28 | 1559 | 1.00 | 0.15 | 529 |
| spring-petclinic | 3635 | jk-tool | 28 | 172 | 1.00 | 0.95 | 61 |
| spring-petclinic | 3635 | rql-explore | 28 | 1348 | 0.41 | 0.00 | 2457 |
| spring-petclinic | 3635 | rql-tool | 28 | 136 | 0.12 | 0.92 | 524 |
| commons-cli | 17862 | graphify-query | 37 | 1628 | 0.59 | 0.07 | 1120 |
| commons-cli | 17862 | graphify-tool | 37 | 145 | 0.68 | 0.56 | 164 |
| commons-cli | 17862 | grep-read | 37 | 24509 | 0.96 | 0.29 | 11328 |
| commons-cli | 17862 | jk-query | 37 | 1569 | 0.96 | 0.07 | 527 |
| commons-cli | 17862 | jk-tool | 37 | 142 | 1.00 | 0.86 | 54 |
| commons-cli | 17862 | rql-explore | 37 | 1851 | 0.45 | 0.00 | 2000 |
| commons-cli | 17862 | rql-tool | 37 | 193 | 0.17 | 0.63 | 1644 |
| commons-io | 105209 | graphify-query | 50 | 1622 | 0.54 | 0.08 | 1411 |
| commons-io | 105209 | graphify-tool | 50 | 142 | 0.60 | 0.46 | 139 |
| commons-io | 105209 | grep-read | 50 | 26037 | 0.97 | 0.23 | 21019 |
| commons-io | 105209 | jk-query | 50 | 1566 | 0.99 | 0.10 | 578 |
| commons-io | 105209 | jk-tool | 50 | 118 | 1.00 | 0.82 | 56 |
| commons-io | 105209 | rql-explore | 50 | 1824 | 0.31 | 0.00 | 2935 |
| commons-io | 105209 | rql-tool | 50 | 388 | 0.45 | 0.49 | 686 |
| commons-collections | 129244 | graphify-query | 50 | 1621 | 0.45 | 0.08 | 1096 |
| commons-collections | 129244 | graphify-tool | 50 | 184 | 0.71 | 0.64 | 80 |
| commons-collections | 129244 | grep-read | 50 | 29064 | 0.95 | 0.28 | 12785 |
| commons-collections | 129244 | jk-query | 50 | 1567 | 0.85 | 0.11 | 563 |
| commons-collections | 129244 | jk-tool | 50 | 190 | 0.99 | 0.91 | 50 |
| commons-collections | 129244 | rql-explore | 50 | 1720 | 0.19 | 0.00 | 4489 |
| commons-collections | 129244 | rql-tool | 50 | 225 | 0.16 | 0.48 | 1499 |
| commons-lang | 188332 | graphify-query | 50 | 1622 | 0.57 | 0.12 | 794 |
| commons-lang | 188332 | graphify-tool | 50 | 150 | 0.61 | 0.49 | 111 |
| commons-lang | 188332 | grep-read | 50 | 22696 | 0.96 | 0.33 | 20371 |
| commons-lang | 188332 | jk-query | 50 | 1562 | 0.95 | 0.11 | 454 |
| commons-lang | 188332 | jk-tool | 50 | 185 | 0.98 | 0.78 | 74 |
| commons-lang | 188332 | rql-explore | 50 | 1758 | 0.33 | 0.00 | 3128 |
| commons-lang | 188332 | rql-tool | 50 | 197 | 0.38 | 0.55 | 680 |
| jk | 679533 | graphify-query | 50 | 1626 | 0.56 | 0.07 | 1107 |
| jk | 679533 | graphify-tool | 50 | 87 | 0.48 | 0.46 | 128 |
| jk | 679533 | grep-read | 50 | 32527 | 0.97 | 0.32 | 17482 |
| jk | 679533 | jk-query | 50 | 1565 | 0.91 | 0.07 | 573 |
| jk | 679533 | jk-tool | 50 | 168 | 1.00 | 1.00 | 52 |
| jk | 679533 | rql-explore | 50 | 1568 | 0.21 | 0.00 | 3359 |
| jk | 679533 | rql-tool | 50 | 166 | 0.39 | 0.79 | 453 |

## By question kind

| Repo | Kind | Arm | Median tokens | Mean recall |
|---|---|---|---:|---:|
| commons-cli | callers | graphify-query | 1634 | 0.45 |
| commons-cli | callers | graphify-tool | 143 | 0.55 |
| commons-cli | callers | grep-read | 33877 | 1.00 |
| commons-cli | callers | jk-query | 1574 | 0.92 |
| commons-cli | callers | jk-tool | 220 | 1.00 |
| commons-cli | callers | rql-explore | 1848 | 0.28 |
| commons-cli | callers | rql-tool | 771 | 0.00 |
| commons-cli | definition | graphify-query | 1625 | 0.90 |
| commons-cli | definition | graphify-tool | 270 | 1.00 |
| commons-cli | definition | grep-read | 11056 | 1.00 |
| commons-cli | definition | jk-query | 1564 | 1.00 |
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
| commons-cli | overrides | jk-query | 1555 | 1.00 |
| commons-cli | overrides | jk-tool | 214 | 1.00 |
| commons-cli | overrides | rql-explore | 1788 | 1.00 |
| commons-cli | overrides | rql-tool | 575 | 1.00 |
| commons-collections | callers | graphify-query | 1536 | 0.35 |
| commons-collections | callers | graphify-tool | 119 | 0.75 |
| commons-collections | callers | grep-read | 37088 | 0.99 |
| commons-collections | callers | jk-query | 1572 | 0.95 |
| commons-collections | callers | jk-tool | 248 | 0.97 |
| commons-collections | callers | rql-explore | 1748 | 0.06 |
| commons-collections | callers | rql-tool | 705 | 0.01 |
| commons-collections | definition | graphify-query | 1620 | 0.70 |
| commons-collections | definition | graphify-tool | 209 | 0.90 |
| commons-collections | definition | grep-read | 2596 | 1.00 |
| commons-collections | definition | jk-query | 1566 | 1.00 |
| commons-collections | definition | jk-tool | 48 | 1.00 |
| commons-collections | definition | rql-explore | 1706 | 0.70 |
| commons-collections | definition | rql-tool | 14 | 0.30 |
| commons-collections | implementers | graphify-query | 1619 | 0.80 |
| commons-collections | implementers | graphify-tool | 155 | 0.90 |
| commons-collections | implementers | grep-read | 14114 | 0.77 |
| commons-collections | implementers | jk-query | 1556 | 0.84 |
| commons-collections | implementers | jk-tool | 162 | 1.00 |
| commons-collections | implementers | rql-explore | 1686 | 0.06 |
| commons-collections | implementers | rql-tool | 226 | 0.00 |
| commons-collections | overrides | graphify-query | 1630 | 0.05 |
| commons-collections | overrides | graphify-tool | 267 | 0.23 |
| commons-collections | overrides | grep-read | 135626 | 1.00 |
| commons-collections | overrides | jk-query | 1570 | 0.50 |
| commons-collections | overrides | jk-tool | 200 | 1.00 |
| commons-collections | overrides | rql-explore | 1714 | 0.06 |
| commons-collections | overrides | rql-tool | 107 | 0.50 |
| commons-io | callers | graphify-query | 1529 | 0.40 |
| commons-io | callers | graphify-tool | 69 | 0.48 |
| commons-io | callers | grep-read | 81058 | 0.97 |
| commons-io | callers | jk-query | 1568 | 0.97 |
| commons-io | callers | jk-tool | 199 | 1.00 |
| commons-io | callers | rql-explore | 1830 | 0.24 |
| commons-io | callers | rql-tool | 1040 | 0.24 |
| commons-io | definition | graphify-query | 1616 | 0.80 |
| commons-io | definition | graphify-tool | 203 | 1.00 |
| commons-io | definition | grep-read | 4359 | 1.00 |
| commons-io | definition | jk-query | 1566 | 1.00 |
| commons-io | definition | jk-tool | 50 | 1.00 |
| commons-io | definition | rql-explore | 1726 | 0.70 |
| commons-io | definition | rql-tool | 26 | 0.30 |
| commons-io | implementers | graphify-query | 1626 | 0.92 |
| commons-io | implementers | graphify-tool | 122 | 0.90 |
| commons-io | implementers | grep-read | 10228 | 0.93 |
| commons-io | implementers | jk-query | 1561 | 1.00 |
| commons-io | implementers | jk-tool | 109 | 1.00 |
| commons-io | implementers | rql-explore | 1860 | 0.20 |
| commons-io | implementers | rql-tool | 175 | 0.58 |
| commons-io | overrides | graphify-query | 1632 | 0.20 |
| commons-io | overrides | graphify-tool | 182 | 0.15 |
| commons-io | overrides | grep-read | 81936 | 1.00 |
| commons-io | overrides | jk-query | 1566 | 1.00 |
| commons-io | overrides | jk-tool | 85 | 1.00 |
| commons-io | overrides | rql-explore | 1828 | 0.20 |
| commons-io | overrides | rql-tool | 640 | 0.90 |
| commons-lang | callers | graphify-query | 1414 | 0.47 |
| commons-lang | callers | graphify-tool | 94 | 0.60 |
| commons-lang | callers | grep-read | 36962 | 1.00 |
| commons-lang | callers | jk-query | 1564 | 0.92 |
| commons-lang | callers | jk-tool | 211 | 0.95 |
| commons-lang | callers | rql-explore | 1793 | 0.17 |
| commons-lang | callers | rql-tool | 360 | 0.07 |
| commons-lang | definition | graphify-query | 1618 | 0.70 |
| commons-lang | definition | graphify-tool | 272 | 0.70 |
| commons-lang | definition | grep-read | 13531 | 1.00 |
| commons-lang | definition | jk-query | 1569 | 1.00 |
| commons-lang | definition | jk-tool | 56 | 1.00 |
| commons-lang | definition | rql-explore | 1736 | 1.00 |
| commons-lang | definition | rql-tool | 48 | 0.50 |
| commons-lang | implementers | graphify-query | 1627 | 0.86 |
| commons-lang | implementers | graphify-tool | 144 | 0.98 |
| commons-lang | implementers | grep-read | 7494 | 0.78 |
| commons-lang | implementers | jk-query | 1552 | 0.92 |
| commons-lang | implementers | jk-tool | 165 | 1.00 |
| commons-lang | implementers | rql-explore | 1777 | 0.04 |
| commons-lang | implementers | rql-tool | 178 | 0.47 |
| commons-lang | overrides | graphify-query | 1640 | 0.33 |
| commons-lang | overrides | graphify-tool | 170 | 0.15 |
| commons-lang | overrides | grep-read | 63120 | 1.00 |
| commons-lang | overrides | jk-query | 1563 | 1.00 |
| commons-lang | overrides | jk-tool | 104 | 1.00 |
| commons-lang | overrides | rql-explore | 1726 | 0.27 |
| commons-lang | overrides | rql-tool | 196 | 0.80 |
| gs-multi-module | definition | graphify-query | 2233 | 1.00 |
| gs-multi-module | definition | graphify-tool | 158 | 1.00 |
| gs-multi-module | definition | grep-read | 171 | 1.00 |
| gs-multi-module | definition | jk-query | 1568 | 1.00 |
| gs-multi-module | definition | jk-tool | 32 | 1.00 |
| gs-multi-module | definition | rql-explore | 262 | 1.00 |
| gs-multi-module | definition | rql-tool | 5 | 0.00 |
| gs-uploading-files | definition | graphify-query | 1626 | 1.00 |
| gs-uploading-files | definition | graphify-tool | 218 | 1.00 |
| gs-uploading-files | definition | grep-read | 551 | 1.00 |
| gs-uploading-files | definition | jk-query | 1568 | 1.00 |
| gs-uploading-files | definition | jk-tool | 32 | 1.00 |
| gs-uploading-files | definition | rql-explore | 936 | 1.00 |
| gs-uploading-files | definition | rql-tool | 12 | 0.30 |
| jk | callers | graphify-query | 1616 | 0.39 |
| jk | callers | graphify-tool | 52 | 0.46 |
| jk | callers | grep-read | 42962 | 1.00 |
| jk | callers | jk-query | 1566 | 0.88 |
| jk | callers | jk-tool | 252 | 1.00 |
| jk | callers | rql-explore | 1570 | 0.04 |
| jk | callers | rql-tool | 869 | 0.03 |
| jk | definition | graphify-query | 1636 | 0.90 |
| jk | definition | graphify-tool | 138 | 0.90 |
| jk | definition | grep-read | 6304 | 0.90 |
| jk | definition | jk-query | 1568 | 1.00 |
| jk | definition | jk-tool | 32 | 1.00 |
| jk | definition | rql-explore | 1525 | 0.40 |
| jk | definition | rql-tool | 17 | 0.30 |
| jk | implementers | graphify-query | 1628 | 0.82 |
| jk | implementers | graphify-tool | 62 | 0.56 |
| jk | implementers | grep-read | 7954 | 0.98 |
| jk | implementers | jk-query | 1556 | 0.90 |
| jk | implementers | jk-tool | 98 | 1.00 |
| jk | implementers | rql-explore | 1578 | 0.56 |
| jk | implementers | rql-tool | 109 | 0.88 |
| jk | overrides | graphify-query | 1621 | 0.30 |
| jk | overrides | graphify-tool | 122 | 0.02 |
| jk | overrides | grep-read | 80025 | 0.97 |
| jk | overrides | jk-query | 1563 | 0.90 |
| jk | overrides | jk-tool | 108 | 1.00 |
| jk | overrides | rql-explore | 1524 | 0.02 |
| jk | overrides | rql-tool | 196 | 0.70 |
| spring-petclinic | callers | graphify-query | 1638 | 0.94 |
| spring-petclinic | callers | graphify-tool | 185 | 0.89 |
| spring-petclinic | callers | grep-read | 7666 | 1.00 |
| spring-petclinic | callers | jk-query | 1570 | 1.00 |
| spring-petclinic | callers | jk-tool | 220 | 1.00 |
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
| spring-petclinic | implementers | jk-query | 1537 | 1.00 |
| spring-petclinic | implementers | jk-tool | 109 | 1.00 |
| spring-petclinic | implementers | rql-explore | 1002 | 0.00 |
| spring-petclinic | implementers | rql-tool | 119 | 0.79 |
| spring-petclinic-kotlin | callers | graphify-query | 103 | 0.09 |
| spring-petclinic-kotlin | callers | graphify-tool | 20 | 0.00 |
| spring-petclinic-kotlin | callers | grep-read | 2169 | 0.50 |
| spring-petclinic-kotlin | callers | jk-query | 1568 | 0.99 |
| spring-petclinic-kotlin | callers | jk-tool | 198 | 0.98 |
| spring-petclinic-kotlin | callers | rql-explore | 356 | 0.00 |
| spring-petclinic-kotlin | callers | rql-tool | 110 | 0.00 |
| spring-petclinic-kotlin | definition | graphify-query | 1609 | 0.80 |
| spring-petclinic-kotlin | definition | graphify-tool | 62 | 0.90 |
| spring-petclinic-kotlin | definition | grep-read | 619 | 1.00 |
| spring-petclinic-kotlin | definition | jk-query | 1552 | 1.00 |
| spring-petclinic-kotlin | definition | jk-tool | 32 | 1.00 |
| spring-petclinic-kotlin | definition | rql-explore | 356 | 0.90 |
| spring-petclinic-kotlin | definition | rql-tool | 61 | 1.00 |
| spring-petclinic-kotlin | implementers | graphify-query | 4662 | 1.00 |
| spring-petclinic-kotlin | implementers | graphify-tool | 89 | 1.00 |
| spring-petclinic-kotlin | implementers | grep-read | 1269 | 0.79 |
| spring-petclinic-kotlin | implementers | jk-query | 1548 | 1.00 |
| spring-petclinic-kotlin | implementers | jk-tool | 110 | 1.00 |
| spring-petclinic-kotlin | implementers | rql-explore | 738 | 0.00 |
| spring-petclinic-kotlin | implementers | rql-tool | 69 | 0.00 |

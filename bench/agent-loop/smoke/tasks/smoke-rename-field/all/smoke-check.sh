# Hidden: copied in after the agent. The template follows the property; the column keeps its name.
grep -q "account.holder" src/main/resources/templates/statement.html && grep -q "owner VARCHAR" src/main/resources/db/schema.sql

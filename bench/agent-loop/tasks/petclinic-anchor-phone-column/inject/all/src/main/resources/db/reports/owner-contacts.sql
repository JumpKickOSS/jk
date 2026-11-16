-- The front desk's contact sheet, exported nightly by the reports job.
SELECT o.last_name, o.first_name, o.telephone
FROM owners o
ORDER BY o.last_name, o.first_name;

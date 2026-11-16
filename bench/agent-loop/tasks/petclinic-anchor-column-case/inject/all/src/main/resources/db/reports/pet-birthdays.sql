-- Birthday cards for this month, exported by the front desk's reports job.
SELECT P.NAME, P.BIRTH_DATE
FROM PETS P
WHERE P.BIRTH_DATE IS NOT NULL
ORDER BY P.BIRTH_DATE;

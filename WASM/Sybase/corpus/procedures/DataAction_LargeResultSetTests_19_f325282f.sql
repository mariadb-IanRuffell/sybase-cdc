-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/LargeResultSetTests.cs (near line 19)
-- License: Apache-2.0; retain upstream license and source notices.
CREATE PROCEDURE [dbo].[large-results]
(
    @numberOfRecords INT
)
AS
BEGIN
    DECLARE @index INT
    SET @index = 0

    DECLARE @text CHAR(4000)
    SET @text = REPLICATE('X', 4000)

    CREATE TABLE #thousand (Id INT, Description0 TEXT)

    WHILE (@index < 1000)
    BEGIN
        INSERT INTO #thousand(Id, Description0) VALUES(@index, @text)

        SET @index = @index + 1
    END

    IF(@numberOfRecords = 1000)
    BEGIN
        SELECT Id, Description0 FROM #thousand
    END
    IF(@numberOfRecords = 10000)
    BEGIN
        SELECT TOP 10000 t1.Id, t1.Description0 FROM #thousand t1, #thousand as t2
    END
    IF(@numberOfRecords = 100000)
    BEGIN
        SELECT TOP 100000 t1.Id, t1.Description0 FROM #thousand t1, #thousand as t2
    END
    IF(@numberOfRecords = 500000)
    BEGIN
        SELECT TOP 500000 t1.Id, t1.Description0 FROM #thousand t1, #thousand as t2
    END

    DROP TABLE #thousand
END
GO

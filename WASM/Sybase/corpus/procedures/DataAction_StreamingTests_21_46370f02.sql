-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/StreamingTests.cs (near line 21)
-- License: Apache-2.0; retain upstream license and source notices.
CREATE PROCEDURE [dbo].[stream-messages]
(
    @sleepTime CHAR(8),
    @numberOfLoops INT
)
AS
BEGIN
    DECLARE @index INT
    SET @index = 0

    WHILE (@index < @numberOfLoops)
    BEGIN
        PRINT 'General Kenobi'

        SET @index = @index + 1
        WAITFOR DELAY @sleepTime
    END
END
GO

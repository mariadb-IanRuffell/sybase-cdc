-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/NamedParamsTests.cs (near line 83)
-- License: Apache-2.0; retain upstream license and source notices.
CREATE PROCEDURE [EchoParameter]
(
    @parameter1 VARCHAR(256),
    @parameter2 VARCHAR(256)
)
AS 
BEGIN 
    SELECT @parameter1 + ' ' + @parameter2 AS Value
END
GO

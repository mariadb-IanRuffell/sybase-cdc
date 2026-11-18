-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/TextEchoProcedureTests.cs (near line 74)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_just_test_echo_text]
  @input text,
  @output int output
as
begin
  set @output = 10
end
GO

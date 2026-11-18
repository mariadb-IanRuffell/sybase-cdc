-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/TextEchoProcedureTests.cs (near line 85)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_insert_test_echo_text]
  @input text,
  @output int output
as
begin
  insert into [dbo].[test_text_table] (Fragment) VALUES (@input)
  set @output = 10
end
GO

-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/TextAllocatorProcedureTests.cs (near line 13)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_test_true_echo_text_locator]
  @input text_locator,
  @output int output
as
begin
  set @output = 100
end
GO

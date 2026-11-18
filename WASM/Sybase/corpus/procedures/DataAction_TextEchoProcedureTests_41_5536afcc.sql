-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/TextEchoProcedureTests.cs (near line 41)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_test_true_echo_text_locator]
  @input text_locator,
  @output text output
as
DECLARE
    @sCommentTL     text_locator

begin
  SELECT @sCommentTL = create_locator(text_locator, @input)
  set @output = @input
  --select @sCommentTL
end
GO

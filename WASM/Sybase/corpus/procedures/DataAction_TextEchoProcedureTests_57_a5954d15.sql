-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/TextEchoProcedureTests.cs (near line 57)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_test_text_type_coloumn]
  @text_locator_input text_locator,
  @text_input text,
  @output int output
as
DECLARE
    @sCommentTL     text_locator

begin
  --SELECT @sCommentTL = create_locator(text_locator, @text_input)
  set @output = 10
  --select @sCommentTL
end
GO

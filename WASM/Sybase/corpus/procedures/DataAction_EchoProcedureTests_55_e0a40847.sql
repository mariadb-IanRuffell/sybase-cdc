-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/EchoProcedureTests.cs (near line 55)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_test_echo_binary]
  @input binary(255),
  @output binary(255) output
as
begin
  set @output = @input
end
GO

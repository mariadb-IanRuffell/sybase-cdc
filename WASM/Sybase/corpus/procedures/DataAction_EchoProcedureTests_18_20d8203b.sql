-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/EchoProcedureTests.cs (near line 18)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_test_echo]
  @nEchoValue int,
  @nEchoValueReturn int OUTPUT
as
begin
  set @nEchoValueReturn = @nEchoValue
  select @nEchoValue as t1Echo
  select @nEchoValue + 1 as t2Echo
  return @nEchoValue
end
GO

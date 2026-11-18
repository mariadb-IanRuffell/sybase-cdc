-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/MultiTypeEchoProcedureTests.cs (near line 13)
-- License: Apache-2.0; retain upstream license and source notices.
create procedure [dbo].[sp_multitype_output]
    @echoChar char(1) = null output,
    @inBinary binary(16),
    @echoBinary binary(16) = null output
as begin
    set @echoChar = 'X'
    set @echoBinary = @inBinary
end
GO

-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/MessageOrderTests.cs (near line 14)
-- License: Apache-2.0; retain upstream license and source notices.
CREATE PROCEDURE [dbo].[sp_test_message_order] (@runSelect char = 'Y', @status varchar(10) output)
AS
BEGIN

PRINT 'Report Header'
PRINT 'Table 1 Header'
if @runSelect = 'Y'
begin
   select 'value1'
end
PRINT 'Empty Table 2 Header'
if @runSelect = 'Y'
begin
   select 'value2' where 1 = 2
end
PRINT 'Table 3 Header'
if @runSelect = 'Y'
begin
   select 'value3'
end
PRINT 'Report Trailer'

select @status = 'OK'

END
GO

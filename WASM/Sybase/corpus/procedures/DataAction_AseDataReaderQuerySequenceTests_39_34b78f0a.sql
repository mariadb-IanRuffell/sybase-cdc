-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/AseDataReaderQuerySequenceTests.cs (near line 39)
-- License: Apache-2.0; retain upstream license and source notices.
create or replace procedure dbo.Test_Select_Insert as select top 1 * from records_tests insert into records_tests (data_field) values (1)
GO

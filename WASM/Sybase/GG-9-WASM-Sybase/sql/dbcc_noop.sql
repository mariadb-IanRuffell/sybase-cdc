-- DBCC success is explicitly mocked by the GridGain backend; neither command reaches SQL.
create procedure dbcc_noop @database varchar(128)
as
begin
    dbcc checkdb(db_id(@database))
    dbcc checktable('this_table_does_not_exist')
    return 0
end
GO

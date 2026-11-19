-- GridGain metadata adaptation of Roland van Veen's MIT-licensed ASE procedure.
-- Original and notices: ../../../corpus/upstream/Hruodland/sql/sp_#select.sql
-- License: ../../../corpus/upstream/Hruodland/LICENSE
-- Semantics intentionally differ from ASE; see the example README.
create or replace procedure sp_#select
    @table varchar(255), @schema varchar(128) = 'PUBLIC'
as
begin
    set nocount on
    declare @found int, @column varchar(255), @line varchar(1024), @position int
    select @found = count(*) from SYSTEM.TABLES
        where schema_name = @schema and table_name = @table
    if @found = 0
    begin
        print 'Table is undefined.'
        return 1
    end
    declare columns_cursor cursor for
        select column_name from SYSTEM.TABLE_COLUMNS
        where schema_name = @schema and table_name = @table order by column_name
    create table #lines (position int, cmd varchar(1024))
    insert into #lines (position, cmd) values (0, 'SELECT')
    set @position = 1
    open columns_cursor
    fetch columns_cursor into @column
    while @@sqlstatus = 0
    begin
        set @line = '"' + str_replace(@column, '"', '""') + '"'
        if @position = 1
            set @line = ' ' + @line
        else
            set @line = ',' + @line
        insert into #lines (position, cmd) values (@position, @line)
        set @position = @position + 1
        fetch columns_cursor into @column
    end
    close columns_cursor
    deallocate cursor columns_cursor
    set @line = 'FROM "' + str_replace(@schema, '"', '""') + '"."' + str_replace(@table, '"', '""') + '"'
    insert into #lines (position, cmd) values (@position, @line)
    select cmd from #lines order by position
    drop table #lines
    return 0
end
GO

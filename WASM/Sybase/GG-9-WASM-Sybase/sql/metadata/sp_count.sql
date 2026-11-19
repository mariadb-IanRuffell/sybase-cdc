-- GridGain metadata adaptation of Roland van Veen's MIT-licensed ASE procedure.
-- Original and notices: ../../../corpus/upstream/Hruodland/sql/sp_#count.sql
-- License: ../../../corpus/upstream/Hruodland/LICENSE
-- Semantics intentionally differ from ASE; see the example README.
create or replace procedure sp_#count
    @schema varchar(128) = 'PUBLIC', @objname varchar(255) = '%'
as
begin
    set nocount on
    declare @table varchar(255), @rows bigint
    declare tables_cursor cursor for
        select table_name from SYSTEM.TABLES
        where schema_name = @schema and table_name like @objname
          and substring(table_name, 1, 9) <> 'WASM_TMP_'
        order by table_name
    -- Materialize catalog rows before creating this invocation's work table.
    open tables_cursor
    create table #counts (schema_name varchar(128), table_name varchar(255), row_count bigint)
    fetch tables_cursor into @table
    while @@sqlstatus = 0
    begin
        -- Narrow host intrinsic: validated catalog lookup followed by quoted COUNT(*).
        set @rows = GG_ROW_COUNT(@schema, @table)
        insert into #counts (schema_name, table_name, row_count) values (@schema, @table, @rows)
        fetch tables_cursor into @table
    end
    close tables_cursor
    deallocate cursor tables_cursor
    print 'GridGain physical table/index sizes are unavailable; size fields are NULL.'
    select schema_name, table_name, row_count,
           cast(null as decimal(18,3)) as data_mb,
           cast(null as decimal(18,3)) as index_mb,
           cast(null as decimal(18,3)) as total_mb
    from #counts order by schema_name, table_name
    drop table #counts
    return 0
end
GO

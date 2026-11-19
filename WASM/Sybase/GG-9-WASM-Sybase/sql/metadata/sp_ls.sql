-- GridGain metadata adaptation of Roland van Veen's MIT-licensed ASE procedure.
-- Original and notices: ../../../corpus/upstream/Hruodland/sql/sp_#ls.sql
-- License: ../../../corpus/upstream/Hruodland/LICENSE
-- Semantics intentionally differ from ASE; see the example README.
create or replace procedure sp_#ls
    @objname varchar(255) = '%', @schema varchar(128) = 'PUBLIC'
as
begin
    set nocount on
    if @objname = 'D' or @objname = 'P' or @objname = 'TR' or @objname = 'V'
       or @objname = 'R' or @objname = 'SP'
    begin
        print 'Unsupported ASE object category. Use U for tables or S for GridGain system views.'
        return 1
    end
    if @objname = 'S'
    begin
        select view_name as object_name, 'SYSTEM_VIEW' as object_type, schema_name,
               cast(null as varchar(128)) as owner, cast(null as datetime) as created_date
        from SYSTEM.SYSTEM_VIEWS where schema_name = 'SYSTEM' order by view_name
        return 0
    end
    if @objname = 'U'
        set @objname = '%'
    select table_name as object_name, 'TABLE' as object_type, schema_name,
           cast(null as varchar(128)) as owner, cast(null as datetime) as created_date
    from SYSTEM.TABLES
    where schema_name = @schema and table_name like '%' + @objname + '%'
      and substring(table_name, 1, 9) <> 'WASM_TMP_'
    order by table_name
    return 0
end
GO

-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/Hruodland/sap_ase_procs/c11a36833b379470097fe3808343044793de4f60/sql/sp_%23ps.sql (near line 10)
-- License: MIT; see per-file notices; retain upstream license and source notices.
create or replace  procedure  sp_#ps
as
/*
#----------------------------------------------------------------------
Author: Roland van Veen
#----------------------------------------------------------------------
Procedure   : sp_#ps
Description : Display who is doing what in short formatted list
              only for active user-processes and not myself.
              Shows blocking processes below that.
Usage       : sp_#ps
Result      : Resultset with the process information.
Errorcodes  : -
License     : MIT
Conditions
  Pre       : Install as sa or other master db user having read acces to the used tables.
Dependency  : SAP ASE 16.0 (may work with none or few modifications on older versions)
Tables      : -
Note(s)     :
Date       Revision  What
-------------------------------------------------------------------
...
2018-02-09 1.2       +Reduced output width
2018¯02-18 1.3       +status column
-------------------------------------------------------------------
*******************************************************************
*/
begin
    declare
    @errno    int

    set nocount off
    set flushmessage on
    set arithignore on

    select  @errno = 0

    print ' '
    print 'Active -processes not me or system:'
    print '_______________________________________________________________________________________________________________________________________'
    print ' '

    select
          Login = substring(l.name,1,20)
        --, Name = l.fullname
        , Host = substring(ms.hostname,1,15)
        , Application   = substring (ms.program_name,1,20)
        , "Database"    = substring(db_name(ms.dbid),1,20)
        , Status= ms.status
        --, "Transaction" = substring(ms.tran_name,1,20)
        , "Tr-[s]"       = isnull(datediff(ss,tr.starttime,getdate()),0)
        , "Blk-[s]"      = isnull(ms.time_blocked,0)
        , Command = ms.cmd
        , CPU = ms.cpu
        , IO = ms.physical_io
        , Spid=ms.spid
        , OSprocess = ms.kpid
    from
        master..sysprocesses  ms
        inner join master..syslogins l
        on l.suid = ms.suid
        left outer join master..systransactions tr
        on ms.spid=tr.spid
    where rtrim(cmd) not in
             --This list is not complete but sufficient enough.
            ( "MIRROR HANDLER"
            , "NETWORK HANDLER"
            , "CHECKPOINT SLEEP"
            , "AUDIT PROCESS"
            , "HOUSEKEEPER"
            , "DEADLOCK TUNE")
    and   ms.cmd not like "%LAZY%"
    and   ms.spid != @@SPID
    and   ms.suid > 0
    order by CPU


    print " "
    print "Blocking processes"
    print '_______________________________________________________________________________________________________________________________________'
    print " "
    select distinct name=substring(suser_name(p.suid),1,15)
           , p.hostprocess
           , l.spid
           , locktype = substring(v.name,1,30)
           , dbname=substring(db_name(l.dbid),1,15)
           , table_name=substring(object_name(l.id,l.dbid),1,30)
           , "# of locks"=count(l.page)
           , l.class
    from   master..syslocks l, master..spt_values v, master..sysprocesses p
    where  l.type = v.number
    and    v.type = "L"
    and    l.spid = p.blocked
    group by p.suid, p.hostprocess, l.spid, v.name, l.dbid, l.id, l.class
    order by 1,3,4

    select @errno = @@error
    set nocount on
    set flushmessage off

    return(@errno)
end
GO

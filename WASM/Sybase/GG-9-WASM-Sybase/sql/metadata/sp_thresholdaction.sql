-- Explicit no-op replacement for Roland van Veen's MIT-licensed ASE sp_thresholdaction.
-- Original and notices: ../../../corpus/upstream/Hruodland/sql/sp_thresholdaction.sql
-- License: ../../../corpus/upstream/Hruodland/LICENSE
-- No backups, dumps, log truncation, threshold handling or database changes occur.
create or replace procedure sp_thresholdaction
    @dbname varchar(30), @segmentname varchar(30), @space_left int, @status int
as
begin
    print 'MOCK sp_thresholdaction: no dump, cleanup, or log-management action performed.'
    return 0
end
GO

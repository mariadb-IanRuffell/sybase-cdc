-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/DataAction/AdoNetCore.AseClient/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration/AseDataAdapterTests.cs (near line 47)
-- License: Apache-2.0; retain upstream license and source notices.
CREATE PROCEDURE AseDataAdapterTests_Proc1
(
    @p1 INT,
    @p2 INT = NULL,
    @p3 SMALLINT,
    @p4 SMALLINT = NULL,
    @p5 BIGINT,
    @p6 BIGINT = NULL,
    @p7 TINYINT,
    @p8 TINYINT = NULL,

    @p9 UNSIGNED INT,
    @p10 UNSIGNED INT = NULL,
    @p11 UNSIGNED SMALLINT,
    @p12 UNSIGNED SMALLINT = NULL,
    @p13 UNSIGNED BIGINT,
    @p14 UNSIGNED BIGINT = NULL,
    @p15 UNSIGNED TINYINT,
    @p16 UNSIGNED TINYINT = NULL,

    @p17 REAL,
    @p18 REAL = NULL,
    @p19 DOUBLE PRECISION,
    @p20 DOUBLE PRECISION = NULL,
    @p21 NUMERIC(18,6),
    @p22 NUMERIC(18,6) = NULL,

    @p23 MONEY,
    @p24 MONEY = NULL,
    @p25 SMALLMONEY,
    @p26 SMALLMONEY = NULL,

    @p27 BIT,
    @p28 BINARY(16),
    @p29 BINARY(16) = NULL,
    @p30 VARBINARY(16),
    @p31 VARBINARY(16) = NULL,
    @p32 IMAGE,
    @p33 IMAGE = NULL,

    @p34 VARCHAR(512),
    @p35 VARCHAR(512) = NULL,
    @p36 CHAR(512),
    @p37 CHAR(512) = NULL,
    @p38 UNIVARCHAR(512),
    @p39 UNIVARCHAR(512) = NULL,
    @p40 UNICHAR(512),
    @p41 UNICHAR(512) = NULL,
    @p42 TEXT,
    @p43 TEXT = NULL,
    @p44 UNITEXT,
    @p45 UNITEXT = NULL,

    --@p46 BIGDATETIME,
    --@p47 BIGDATETIME = NULL,
    @p48 DATETIME,
    @p49 DATETIME = NULL,
    @p50 SMALLDATETIME,
    @p51 SMALLDATETIME = NULL,
    @p52 DATE,
    @p53 DATE = NULL,

    --@p54 BIGTIME,
    --@p55 BIGTIME = NULL,
    @p56 TIME,
    @p57 TIME = NULL,

    @o1 INT OUTPUT
)
AS 
BEGIN 
    SELECT 
        1 AS id, 
        'something' as description 
    RETURN 0
END
GO

-- Extracted fixture; not executed.
-- Source: https://raw.githubusercontent.com/linq2db/linq2db/d141f5671afe0b6d2d5ab23fcd3b5ae252c2dcc9/Data/Create%20Scripts/Sybase.sql (near line 258)
-- License: MIT; retain upstream license and source notices.
CREATE OR REPLACE PROCEDURE AddIssue792Record AS
	INSERT INTO dbo.AllTypes(char20DataType) VALUES('issue792')
RETURN
GO

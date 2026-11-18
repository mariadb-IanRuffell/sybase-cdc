-- Logical ASE fixture schemas, supplying visible columns and IDENTITY mappings.
-- The runner creates physical GridGain tables, adding a UUID key where ASE had none.
CREATE TABLE records_tests (data_field int NULL)
GO
CREATE TABLE Person (
    PersonID int IDENTITY,
    FirstName nvarchar(50) NOT NULL,
    LastName nvarchar(50) NOT NULL,
    MiddleName nvarchar(50) NULL,
    Gender char(1) NOT NULL
)
GO
CREATE TABLE AllTypes (ID int IDENTITY, char20DataType varchar(20) NULL)
GO
CREATE TABLE test_text_table (Fragment text NULL)
GO

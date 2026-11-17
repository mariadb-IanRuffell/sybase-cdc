CREATE PROCEDURE validate_restock @units INT
AS
BEGIN
    IF @units > 0
        RETURN 0
    RETURN 1
END

CREATE PROCEDURE restock @sku INT, @units INT
AS
BEGIN
    IF @units > 0
    BEGIN
        UPDATE inventory
           SET quantity = quantity + @units
         WHERE sku = @sku
        IF @@rowcount = 0
            RETURN 2
        RETURN 0
    END
    RETURN 1
END

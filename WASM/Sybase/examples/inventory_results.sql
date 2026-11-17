CREATE PROCEDURE dbo.inventory_results @minimum INT = 1, @matches INT OUTPUT
AS
BEGIN
    SET NOCOUNT ON
    SELECT i.sku, SUM(i.quantity) AS available
      FROM dbo.inventory AS i
     GROUP BY i.sku
    HAVING SUM(i.quantity) >= @minimum
     ORDER BY i.sku

    SELECT @matches = @@rowcount
    SELECT @matches AS matching_skus, @minimum AS requested_minimum
    RETURN 0
END
GO

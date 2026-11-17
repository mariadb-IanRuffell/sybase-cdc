CREATE OR REPLACE PROCEDURE dbo.inventory_summary
    @minimum int = 1,
    @matches int OUTPUT
AS
BEGIN
    SET NOCOUNT ON

    SELECT i.sku, SUM(i.quantity) AS available
      INTO #stock
      FROM dbo.inventory AS i
     GROUP BY i.sku
    HAVING SUM(i.quantity) >= @minimum

    SELECT @matches = @@rowcount
    IF @matches = 0
        RETURN 2

    SELECT s.sku, s.available,
           CASE WHEN s.available < 5 THEN 'low' ELSE 'ready' END AS stock_level
      FROM #stock AS s
     ORDER BY s.available DESC, s.sku
    RETURN 0
END
GO

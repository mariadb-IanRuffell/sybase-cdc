-- Exercise typed values through WASM -> GridGain SQL -> WASM -> JSON.
create procedure typed_roundtrip
    @tiny tinyint output, @small smallint output, @big bigint output,
    @huge unsigned bigint output, @amount numeric(38,9) output,
    @cash money output, @smallcash smallmoney output,
    @real_value real output, @float_value float output,
    @text_value univarchar(80) output, @fixed char(4) output,
    @bytes varbinary(16) output, @version timestamp output,
    @day date output, @clock bigtime output, @instant bigdatetime output,
    @flag bit output, @nullable varchar(10) = null output
as begin
    select @tiny=@tiny, @small=@small, @big=@big, @huge=@huge,
           @amount=@amount, @cash=@cash, @smallcash=@smallcash,
           @real_value=@real_value, @float_value=@float_value,
           @text_value=@text_value, @fixed=@fixed, @bytes=@bytes,
           @version=@version, @day=@day, @clock=@clock, @instant=@instant,
           @flag=@flag, @nullable=@nullable
    select @amount as amount, @big as big_value, @huge as huge_value,
           @text_value as text_value, @bytes as bytes_value,
           @instant as instant_value, @nullable as nullable_value
    return 0
end
go

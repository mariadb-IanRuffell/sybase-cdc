(module
  (import "ignite" "update_0" (func $update_0 (param i32 i32) (result i32)))
  (func (export "restock") (param $sku i32) (param $units i32) (result i32)
    (local $@@rowcount i32)
    local.get $units
    i32.const 0
    i32.gt_s
    i32.const 0
    local.set $@@rowcount
    if
      local.get $units
      local.get $sku
      call $update_0
      local.set $@@rowcount
      local.get $@@rowcount
      i32.const 0
      i32.eq
      i32.const 0
      local.set $@@rowcount
      if
        i32.const 2
        return
      end
      i32.const 0
      return
    end
    i32.const 1
    return
  )
)

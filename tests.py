import io
import unittest
import zipfile

from core import AddressedItem, EncoderChest, _Int, _List, _LongArray, chest_inventory, export_package, plan_summary, read_nbt_gzip, read_waterway_litematic, write_encoder_litematic, write_nbt_gzip, _pack_states


class EncoderTests(unittest.TestCase):
    def test_fixed_ss_inventory_template(self):
        chest = EncoderChest("第1段", "00001", 1, tuple(AddressedItem(f"minecraft:stone_{i}",0,0,i,"0000000000","fixture") for i in range(17)))
        inventory = chest_inventory(chest)
        self.assertEqual(len(inventory), 54)
        self.assertEqual(sum(1 for item in inventory[:50] if item["id"] == "minecraft:paper"), 33)
        self.assertEqual(int(inventory[50]["count"]), 5)
        self.assertEqual([item["id"] for item in inventory[51:]], ["minecraft:shears"] * 3)

    def test_litematic_has_two_chest_halves(self):
        chest = EncoderChest("第1段", "1", 1, (AddressedItem("minecraft:stone",0,0,0,"1","fixture"),))
        root = read_nbt_gzip(write_encoder_litematic([chest]))
        entities = root["Regions"]["encoder_chests"]["TileEntities"]
        self.assertEqual(len(entities), 2)
        self.assertEqual(len(entities[0]["Items"]), 27)
        self.assertEqual(len(entities[1]["Items"]), 27)

    def test_export_package(self):
        cfg={"project_name":"test","radix":2,"threads":1,"waterways":1,"total_digits":10}
        package=export_package(cfg,[{"thread":0,"waterway":0,"name":"fixture","items":["minecraft:stone","minecraft:dirt"]}])
        with zipfile.ZipFile(io.BytesIO(package)) as archive:
            self.assertIn("encoder.litematic",archive.namelist())
            self.assertIn("mapping.csv",archive.namelist())

    def test_reads_single_barrels_in_x_order(self):
        palette = _List(10, [{"Name":"minecraft:air"}, {"Name":"minecraft:barrel", "Properties":{"facing":"north","open":"false"}}])
        first = {"id":"minecraft:barrel","x":_Int(0),"y":_Int(0),"z":_Int(0),"Items":_List(10,[{"Slot":_Int(0),"id":"minecraft:stone","count":_Int(1)}])}
        second = {"id":"minecraft:barrel","x":_Int(1),"y":_Int(0),"z":_Int(0),"Items":_List(10,[{"Slot":_Int(0),"id":"minecraft:dirt","count":_Int(1)}])}
        region={"Position":{"x":_Int(0),"y":_Int(0),"z":_Int(0)},"Size":{"x":_Int(2),"y":_Int(1),"z":_Int(1)},"BlockStatePalette":palette,"BlockStates":_pack_states([1,1],2),"TileEntities":_List(10,[first,second])}
        raw=write_nbt_gzip({"Regions":{"fixture":region}})
        waterway=read_waterway_litematic(raw,"fixture")
        self.assertEqual(waterway.items,("minecraft:stone","minecraft:dirt"))

    def test_binary_four_threads_reserve_two_high_bits(self):
        config={"project_name":"four","radix":2,"threads":4,"waterways":8,"total_digits":10,"max_group_width":5}
        waterways=[]
        for thread in range(4):
            for waterway in range(8):
                waterways.append({"thread":thread,"waterway":waterway,"name":f"t{thread}w{waterway}","items":[f"minecraft:stone_{thread}_{waterway}"]})
        plan=plan_summary(config,waterways)
        self.assertEqual(plan["sizing"]["thread_digits"],2)
        self.assertEqual(plan["sizing"]["water_digits"],3)
        self.assertEqual(plan["sizing"]["slot_digits"],5)
        self.assertEqual(sum(plan["partition"]),10)
        self.assertLessEqual(max(plan["partition"]),5)

    def test_decimal_is_single_thread(self):
        config={"project_name":"decimal","radix":10,"threads":1,"waterways":10,"total_digits":3}
        plan=plan_summary(config,[{"thread":0,"waterway":0,"name":"d","items":["minecraft:stone"]}] + [{"thread":0,"waterway":i,"name":f"d{i}","items":[]} for i in range(1,10)])
        self.assertEqual(plan["sizing"]["slot_digits"],2)
        self.assertEqual(plan["partition"],[1,1,1])


if __name__ == "__main__":
    unittest.main()

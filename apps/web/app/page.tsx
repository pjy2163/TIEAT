import { redirect } from "next/navigation";

export default function Home() {
  redirect("/store/meal-usages");
}
